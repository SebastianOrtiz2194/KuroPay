package com.kuropay.wallet;

import com.kuropay.wallet.application.port.in.DepositFundsUseCase;
import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositCommand;
import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositResult;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import com.kuropay.wallet.infrastructure.persistence.SpringDataOutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class DepositConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DepositFundsUseCase depositFundsUseCase;

    @Autowired
    private WalletRepositoryPort walletRepository;

    @Autowired
    private SpringDataOutboxRepository outboxRepository;

    /** Same reason as in TransferConcurrencyIntegrationTest: avoid lazy class init on virtual threads. */
    @BeforeEach
    void warmUpDepositPathOnASingleThreadAndCleanOutbox() {
        Wallet wallet = newEmptyWallet("user-warm-up-deposit-" + UUID.randomUUID());
        depositFundsUseCase.deposit(new DepositCommand(
                wallet.id(), Money.of(1L, Currency.JPY), "warm-up-" + UUID.randomUUID()
        ));
        outboxRepository.deleteAll();
    }

    @Test
    void concurrent_retries_with_the_same_idempotency_key_should_credit_exactly_once() throws Exception {
        Wallet wallet = newEmptyWallet("user-deposit-retry");

        // The same request fired 20 times at once: only PostgreSQL protects us here.
        int retries = 20;
        DepositCommand sameCommand = new DepositCommand(
                wallet.id(), Money.of(1_000L, Currency.JPY), "dep-same-key-retry"
        );
        List<DepositResult> results = runConcurrently(retries, _ -> sameCommand);

        assertThat(results).extracting(DepositResult::depositId)
                .as("every retry must replay the same deposit")
                .containsOnly(results.getFirst().depositId());
        assertThat(results).filteredOn(result -> !result.replayed())
                .as("exactly one request really executed")
                .hasSize(1);
        assertThat(results).extracting(DepositResult::newBalance)
                .as("fresh and replayed responses report the same balance")
                .containsOnly(Money.of(1_000L, Currency.JPY));
        assertThat(walletRepository.findById(wallet.id()).orElseThrow().balance())
                .isEqualTo(Money.of(1_000L, Currency.JPY));
        assertThat(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).hasSize(1);
    }

    @Test
    void concurrent_deposits_with_different_keys_should_not_lose_any_credit() throws Exception {
        Wallet wallet = newEmptyWallet("user-deposit-parallel");

        int deposits = 50;
        List<DepositResult> results = runConcurrently(deposits, index -> new DepositCommand(
                wallet.id(), Money.of(1_000L, Currency.JPY), "dep-parallel-" + index
        ));

        assertThat(results).noneMatch(DepositResult::replayed);
        // Without the row lock two deposits would read the same balance and one credit would vanish.
        assertThat(walletRepository.findById(wallet.id()).orElseThrow().balance())
                .isEqualTo(Money.of(50_000L, Currency.JPY));
        // Each deposit saw a different balance: the lock really serialized them.
        assertThat(results).extracting(DepositResult::newBalance).doesNotHaveDuplicates();
        assertThat(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).hasSize(deposits);
    }

    @Test
    void reusing_a_key_for_another_wallet_should_be_rejected_and_leave_that_wallet_untouched() {
        Wallet first = newEmptyWallet("user-deposit-key-owner");
        Wallet second = newEmptyWallet("user-deposit-key-thief");
        depositFundsUseCase.deposit(new DepositCommand(first.id(), Money.of(1_000L, Currency.JPY), "dep-shared-key"));

        assertThatThrownBy(() -> depositFundsUseCase.deposit(
                new DepositCommand(second.id(), Money.of(1_000L, Currency.JPY), "dep-shared-key")
        )).isInstanceOf(DuplicateIdempotencyKeyException.class);

        assertThat(walletRepository.findById(second.id()).orElseThrow().balance())
                .isEqualTo(Money.zero(Currency.JPY));
        assertThat(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).hasSize(1);
    }

    private Wallet newEmptyWallet(String ownerId) {
        Wallet wallet = Wallet.createNew(ownerId, Currency.JPY, Instant.now());
        walletRepository.save(wallet);
        return wallet;
    }

    /** Releases all tasks at the same instant so they really contend for the wallet row. */
    private List<DepositResult> runConcurrently(int tasks, CommandFactory commandFor) throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(tasks);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<DepositResult>> futures = new ArrayList<>(tasks);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                DepositCommand command = commandFor.create(i);
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startGun.await();
                    return depositFundsUseCase.deposit(command);
                }));
            }
            readyLatch.await();
            startGun.countDown();
        }

        List<DepositResult> results = new ArrayList<>(tasks);
        for (Future<DepositResult> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @FunctionalInterface
    private interface CommandFactory {
        DepositCommand create(int index);
    }
}
