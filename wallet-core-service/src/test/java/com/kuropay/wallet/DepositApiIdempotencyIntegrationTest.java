package com.kuropay.wallet;

import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import com.kuropay.wallet.infrastructure.web.DepositController.DepositRequest;
import com.kuropay.wallet.infrastructure.web.DepositController.DepositResponse;
import com.kuropay.wallet.infrastructure.web.TransferController.TransferRequest;
import com.kuropay.wallet.infrastructure.web.TransferController.TransferResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Full stack over real HTTP: controller, Redis cache, use case and PostgreSQL together. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DepositApiIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Autowired
    private WalletRepositoryPort walletRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void duplicate_deposit_with_the_same_key_should_credit_once_and_replay_the_cached_response() {
        Wallet wallet = newWallet("user-dep-api-replay", 0L);
        DepositRequest request = new DepositRequest(BigDecimal.valueOf(1_000L), Currency.JPY);

        ResponseEntity<DepositResponse> first = postDeposit(wallet.id(), "dep-api-replay-001", request);
        ResponseEntity<DepositResponse> retry = postDeposit(wallet.id(), "dep-api-replay-001", request);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("false");
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().newBalance()).isEqualByComparingTo("1000");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());

        assertThat(balanceOf(wallet)).isEqualTo(Money.of(1_000L, Currency.JPY));
    }

    @Test
    void retry_after_redis_lost_its_data_should_replay_from_postgres_and_not_credit_twice() {
        Wallet wallet = newWallet("user-dep-api-redis-loss", 0L);
        DepositRequest request = new DepositRequest(BigDecimal.valueOf(1_000L), Currency.JPY);

        ResponseEntity<DepositResponse> first = postDeposit(wallet.id(), "dep-api-redis-loss-001", request);
        flushRedis();
        ResponseEntity<DepositResponse> retry = postDeposit(wallet.id(), "dep-api-redis-loss-001", request);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        // Redis knew nothing, but the use case replayed from the deposits table and says so.
        assertThat(retry.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(wallet)).isEqualTo(Money.of(1_000L, Currency.JPY));
    }

    @Test
    void reusing_a_key_for_a_different_wallet_should_return_422_from_both_cache_and_database() {
        Wallet first = newWallet("user-dep-api-reuse-a", 0L);
        Wallet other = newWallet("user-dep-api-reuse-b", 0L);
        DepositRequest request = new DepositRequest(BigDecimal.valueOf(1_000L), Currency.JPY);
        String key = "dep-api-reuse-001";

        postDeposit(first.id(), key, request);

        // 1) Rejected by the Redis fingerprint check (the wallet id is part of the fingerprint).
        ResponseEntity<ProblemDetail> fromCache = postDepositExpectingProblem(other.id(), key, request);
        // 2) Rejected by the UNIQUE constraint once Redis has lost the entry.
        flushRedis();
        ResponseEntity<ProblemDetail> fromDatabase = postDepositExpectingProblem(other.id(), key, request);

        assertThat(fromCache.getStatusCode().value()).isEqualTo(422);
        assertThat(fromDatabase.getStatusCode().value()).isEqualTo(422);
        assertThat(balanceOf(first)).isEqualTo(Money.of(1_000L, Currency.JPY));
        assertThat(balanceOf(other)).isEqualTo(Money.zero(Currency.JPY));
    }

    @Test
    void the_same_key_used_for_a_transfer_and_a_deposit_should_not_collide_in_the_cache() {
        Wallet source = newWallet("user-shared-key-source", 10_000L);
        Wallet target = newWallet("user-shared-key-target", 0L);
        String sharedKey = "shared-key-001";

        TransferRequest transferRequest =
                new TransferRequest(source.id(), target.id(), BigDecimal.valueOf(2_500L), Currency.JPY);
        DepositRequest depositRequest = new DepositRequest(BigDecimal.valueOf(1_000L), Currency.JPY);

        ResponseEntity<TransferResponse> transfer = postTransfer(sharedKey, transferRequest);
        // PostgreSQL allows this (one UNIQUE constraint per table), so the cache must allow it too.
        ResponseEntity<DepositResponse> deposit = postDeposit(source.id(), sharedKey, depositRequest);

        assertThat(deposit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deposit.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("false");

        // Each retry must get back ITS OWN original response, not the other operation's entry.
        ResponseEntity<TransferResponse> transferRetry = postTransfer(sharedKey, transferRequest);
        ResponseEntity<DepositResponse> depositRetry = postDeposit(source.id(), sharedKey, depositRequest);

        assertThat(transferRetry.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
        assertThat(transferRetry.getBody()).isEqualTo(transfer.getBody());
        assertThat(depositRetry.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
        assertThat(depositRetry.getBody()).isEqualTo(deposit.getBody());

        // 10 000 - 2 500 (transfer, once) + 1 000 (deposit, once)
        assertThat(balanceOf(source)).isEqualTo(Money.of(8_500L, Currency.JPY));
        assertThat(balanceOf(target)).isEqualTo(Money.of(2_500L, Currency.JPY));
    }

    private Wallet newWallet(String ownerPrefix, long initialBalance) {
        Instant now = Instant.now();
        Wallet wallet = Wallet.createNew(ownerPrefix + "-" + UUID.randomUUID(), Currency.JPY, now);
        if (initialBalance > 0) {
            wallet.credit(Money.of(initialBalance, Currency.JPY), now);
        }
        walletRepository.save(wallet);
        return wallet;
    }

    private Money balanceOf(Wallet wallet) {
        return walletRepository.findById(wallet.id()).orElseThrow().balance();
    }

    /** Same effect as a Redis restart without persistence, or the entry expiring. */
    private void flushRedis() {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private ResponseEntity<DepositResponse> postDeposit(UUID walletId, String idempotencyKey, DepositRequest body) {
        return restClient.post()
                .uri("/api/wallets/{walletId}/deposits", walletId)
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(DepositResponse.class);
    }

    private ResponseEntity<ProblemDetail> postDepositExpectingProblem(
            UUID walletId, String idempotencyKey, DepositRequest body
    ) {
        return restClient.post()
                .uri("/api/wallets/{walletId}/deposits", walletId)
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.value() == 422, (req, res) -> {})
                .toEntity(ProblemDetail.class);
    }

    private ResponseEntity<TransferResponse> postTransfer(String idempotencyKey, TransferRequest body) {
        return restClient.post()
                .uri("/api/transfers")
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(TransferResponse.class);
    }
}
