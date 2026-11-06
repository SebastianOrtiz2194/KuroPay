package com.kuropay.wallet;

import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import com.kuropay.wallet.infrastructure.web.TransferController.TransferRequest;
import com.kuropay.wallet.infrastructure.web.TransferController.TransferResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferApiIdempotencyIntegrationTest {

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

    @Test
    void duplicate_requests_with_same_idempotency_key_should_only_debit_once_and_replay_cached_response() {
        Instant now = Instant.now();
        Wallet source = Wallet.createNew("user-idem-source", Currency.JPY, now);
        source.credit(Money.of(10_000L, Currency.JPY), now);
        walletRepository.save(source);

        Wallet target = Wallet.createNew("user-idem-target", Currency.JPY, now);
        walletRepository.save(target);

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        TransferRequest requestBody = new TransferRequest(
                source.id(),
                target.id(),
                BigDecimal.valueOf(2_500L),
                Currency.JPY
        );

        String idempotencyKey = "idem-double-click-001";

        ResponseEntity<TransferResponse> firstCall = restClient.post()
                .uri("/api/transfers")
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(TransferResponse.class);

        ResponseEntity<TransferResponse> secondCall = restClient.post()
                .uri("/api/transfers")
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(TransferResponse.class);

        assertThat(firstCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstCall.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("false");
        assertThat(firstCall.getBody()).isNotNull();

        assertThat(secondCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondCall.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
        assertThat(secondCall.getBody()).isEqualTo(firstCall.getBody());

        Wallet updatedSource = walletRepository.findById(source.id()).orElseThrow();
        Wallet updatedTarget = walletRepository.findById(target.id()).orElseThrow();

        assertThat(updatedSource.balance()).isEqualTo(Money.of(7_500L, Currency.JPY));
        assertThat(updatedTarget.balance()).isEqualTo(Money.of(2_500L, Currency.JPY));
    }

    @Test
    void retry_after_redis_lost_its_data_should_replay_from_postgres_and_not_debit_twice() {
        Instant now = Instant.now();
        Wallet source = Wallet.createNew("user-redis-loss-source", Currency.JPY, now);
        source.credit(Money.of(10_000L, Currency.JPY), now);
        walletRepository.save(source);

        Wallet target = Wallet.createNew("user-redis-loss-target", Currency.JPY, now);
        walletRepository.save(target);

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        TransferRequest requestBody = new TransferRequest(
                source.id(),
                target.id(),
                BigDecimal.valueOf(2_500L),
                Currency.JPY
        );
        String idempotencyKey = "idem-redis-loss-001";

        ResponseEntity<TransferResponse> firstCall = postTransfer(restClient, idempotencyKey, requestBody);

        // Same effect as: Redis restarted without persistence, the cached response expired, or the
        // service crashed between the DB commit and the Redis write.
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        ResponseEntity<TransferResponse> retry = postTransfer(restClient, idempotencyKey, requestBody);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(firstCall.getBody());
        // Redis knew nothing, but the use case replayed from the transfers table and says so.
        assertThat(retry.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");

        assertThat(walletRepository.findById(source.id()).orElseThrow().balance())
                .isEqualTo(Money.of(7_500L, Currency.JPY));
        assertThat(walletRepository.findById(target.id()).orElseThrow().balance())
                .isEqualTo(Money.of(2_500L, Currency.JPY));
    }

    @Test
    void reusing_an_idempotency_key_with_a_different_payload_should_return_422_from_both_cache_and_database() {
        Instant now = Instant.now();
        Wallet source = Wallet.createNew("user-key-reuse-source", Currency.JPY, now);
        source.credit(Money.of(10_000L, Currency.JPY), now);
        walletRepository.save(source);

        Wallet target = Wallet.createNew("user-key-reuse-target", Currency.JPY, now);
        walletRepository.save(target);

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        String idempotencyKey = "idem-key-reuse-001";

        postTransfer(restClient, idempotencyKey,
                new TransferRequest(source.id(), target.id(), BigDecimal.valueOf(1_000L), Currency.JPY));

        TransferRequest differentAmount =
                new TransferRequest(source.id(), target.id(), BigDecimal.valueOf(9_000L), Currency.JPY);

        // 1) Rejected by the Redis fingerprint check.
        ResponseEntity<ProblemDetail> fromCache = postExpectingProblem(restClient, idempotencyKey, differentAmount);

        // 2) Rejected by comparing against the stored transfer row once Redis has lost the entry.
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        ResponseEntity<ProblemDetail> fromDatabase = postExpectingProblem(restClient, idempotencyKey, differentAmount);

        for (ResponseEntity<ProblemDetail> response : List.of(fromCache, fromDatabase)) {
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Idempotency Key Reused");
        }
        assertThat(walletRepository.findById(source.id()).orElseThrow().balance())
                .isEqualTo(Money.of(9_000L, Currency.JPY));
    }

    private static ResponseEntity<ProblemDetail> postExpectingProblem(
            RestClient restClient, String idempotencyKey, TransferRequest body
    ) {
        return restClient.post()
                .uri("/api/transfers")
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.value() == 422, (req, res) -> {})
                .toEntity(ProblemDetail.class);
    }

    private static ResponseEntity<TransferResponse> postTransfer(
            RestClient restClient, String idempotencyKey, TransferRequest body
    ) {
        return restClient.post()
                .uri("/api/transfers")
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(TransferResponse.class);
    }

    @Test
    void insufficient_funds_should_return_rfc9457_problem_detail_422() {
        Instant now = Instant.now();
        Wallet source = Wallet.createNew("user-poor", Currency.JPY, now);
        source.credit(Money.of(500L, Currency.JPY), now);
        walletRepository.save(source);

        Wallet target = Wallet.createNew("user-merchant", Currency.JPY, now);
        walletRepository.save(target);

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        TransferRequest requestBody = new TransferRequest(
                source.id(),
                target.id(),
                BigDecimal.valueOf(3_000L),
                Currency.JPY
        );

        ResponseEntity<ProblemDetail> response = restClient.post()
                .uri("/api/transfers")
                .header("X-API-Version", "1.0")
                .header("Idempotency-Key", "idem-insufficient-001")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .onStatus(status -> status.value() == 422, (req, res) -> {})
                .toEntity(ProblemDetail.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Insufficient Funds");
    }
}
