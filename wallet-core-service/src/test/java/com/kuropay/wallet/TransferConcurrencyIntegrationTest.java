package com.kuropay.wallet;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferCommand;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferResult;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import com.kuropay.wallet.infrastructure.persistence.SpringDataOutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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

@SpringBootTest
@Testcontainers
class TransferConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private TransferFundsUseCase transferFundsUseCase;

    @Autowired
    private WalletRepositoryPort walletRepository;

    @Autowired
    private SpringDataOutboxRepository outboxRepository;

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    void fifty_concurrent_virtual_threads_should_never_overdraw_wallet_and_must_record_exact_outbox_events() throws Exception {
        Instant now = Instant.now();
        Wallet source = Wallet.createNew("user-concurrent-source", Currency.JPY, now);
        source.credit(Money.of(10_000L, Currency.JPY), now);
        walletRepository.save(source);

        Wallet target = Wallet.createNew("user-concurrent-target", Currency.JPY, now);
        walletRepository.save(target);

        int concurrentThreads = 50;
        CountDownLatch readyLatch = new CountDownLatch(concurrentThreads);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<TransferResult>> futures = new ArrayList<>(concurrentThreads);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrentThreads; i++) {
                final int index = i;
                Callable<TransferResult> task = () -> {
                    readyLatch.countDown();
                    startGun.await();
                    return transferFundsUseCase.transfer(new TransferCommand(
                            source.id(),
                            target.id(),
                            Money.of(1_000L, Currency.JPY),
                            "idem-concurrent-" + index
                    ));
                };
                futures.add(executor.submit(task));
            }

            readyLatch.await();
            startGun.countDown();
        }

        long completedCount = 0;
        long rejectedCount = 0;
        for (Future<TransferResult> future : futures) {
            TransferResult result = future.get();
            switch (result) {
                case TransferResult.Completed _ -> completedCount++;
                case TransferResult.RejectedInsufficientFunds _ -> rejectedCount++;
            }
        }

        assertThat(completedCount).isEqualTo(10L);
        assertThat(rejectedCount).isEqualTo(40L);

        Wallet updatedSource = walletRepository.findById(source.id()).orElseThrow();
        Wallet updatedTarget = walletRepository.findById(target.id()).orElseThrow();

        assertThat(updatedSource.balance()).isEqualTo(Money.of(0L, Currency.JPY));
        assertThat(updatedTarget.balance()).isEqualTo(Money.of(10_000L, Currency.JPY));
        assertThat(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")).hasSize(10);
    }

    @Test
    void bidirectional_concurrent_transfers_should_complete_without_deadlock_and_conserve_total_money() throws Exception {
        Instant now = Instant.now();
        Wallet walletA = Wallet.createNew("user-a", Currency.JPY, now);
        walletA.credit(Money.of(50_000L, Currency.JPY), now);
        walletRepository.save(walletA);

        Wallet walletB = Wallet.createNew("user-b", Currency.JPY, now);
        walletB.credit(Money.of(50_000L, Currency.JPY), now);
        walletRepository.save(walletB);

        int transfersEachDirection = 20;
        int totalTasks = transfersEachDirection * 2;
        CountDownLatch readyLatch = new CountDownLatch(totalTasks);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<TransferResult>> futures = new ArrayList<>(totalTasks);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < transfersEachDirection; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startGun.await();
                    return transferFundsUseCase.transfer(new TransferCommand(
                            walletA.id(), walletB.id(), Money.of(1_000L, Currency.JPY), "ab-" + idx
                    ));
                }));
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startGun.await();
                    return transferFundsUseCase.transfer(new TransferCommand(
                            walletB.id(), walletA.id(), Money.of(500L, Currency.JPY), "ba-" + idx
                    ));
                }));
            }

            readyLatch.await();
            startGun.countDown();
        }

        for (Future<TransferResult> future : futures) {
            assertThat(future.get()).isInstanceOf(TransferResult.Completed.class);
        }

        Wallet finalA = walletRepository.findById(walletA.id()).orElseThrow();
        Wallet finalB = walletRepository.findById(walletB.id()).orElseThrow();

        // A sent 20 * 1000 (-20,000) and received 20 * 500 (+10,000) -> 40,000
        // B sent 20 * 500 (-10,000) and received 20 * 1000 (+20,000) -> 60,000
        assertThat(finalA.balance()).isEqualTo(Money.of(40_000L, Currency.JPY));
        assertThat(finalB.balance()).isEqualTo(Money.of(60_000L, Currency.JPY));
        assertThat(finalA.balance().add(finalB.balance())).isEqualTo(Money.of(100_000L, Currency.JPY));
    }
}
