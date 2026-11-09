package com.kuropay.wallet;

import com.kuropay.wallet.application.port.in.CreateWalletUseCase;
import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletCommand;
import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletResult;
import com.kuropay.wallet.domain.exception.DuplicateWalletException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class WalletCreationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private CreateWalletUseCase createWalletUseCase;

    @Autowired
    private WalletRepositoryPort walletRepository;

    @Test
    void second_wallet_in_the_same_currency_should_be_rejected_by_the_unique_constraint() {
        String owner = "owner-" + UUID.randomUUID();
        walletRepository.save(Wallet.createNew(owner, Currency.JPY, Instant.now()));

        assertThatThrownBy(() -> walletRepository.save(Wallet.createNew(owner, Currency.JPY, Instant.now())))
                .isInstanceOf(DuplicateWalletException.class)
                .hasMessageContaining(owner);
    }

    @Test
    void same_owner_may_hold_one_wallet_per_currency() {
        String owner = "owner-" + UUID.randomUUID();

        walletRepository.save(Wallet.createNew(owner, Currency.JPY, Instant.now()));
        walletRepository.save(Wallet.createNew(owner, Currency.USD, Instant.now()));

        assertThat(walletRepository.findByOwnerIdAndCurrency(owner, Currency.JPY)).isPresent();
        assertThat(walletRepository.findByOwnerIdAndCurrency(owner, Currency.USD)).isPresent();
        assertThat(walletRepository.findByOwnerIdAndCurrency(owner, Currency.EUR)).isEmpty();
    }

    @Test
    void concurrent_creations_of_the_same_wallet_should_create_exactly_one() throws Exception {
        warmUpCreatePathOnASingleThread();

        String owner = "owner-" + UUID.randomUUID();
        CreateWalletCommand sameCommand = new CreateWalletCommand(owner, Currency.JPY);
        int requests = 10;
        CountDownLatch readyLatch = new CountDownLatch(requests);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<CreateWalletResult>> futures = new ArrayList<>(requests);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startGun.await();
                    return createWalletUseCase.createWallet(sameCommand);
                }));
            }
            readyLatch.await();
            startGun.countDown();
        }

        List<UUID> createdIds = new ArrayList<>();
        List<UUID> existingIds = new ArrayList<>();
        for (Future<CreateWalletResult> future : futures) {
            switch (future.get()) {
                case CreateWalletResult.Created created -> createdIds.add(created.wallet().id());
                case CreateWalletResult.AlreadyExists already -> existingIds.add(already.existing().id());
            }
        }

        assertThat(createdIds).as("exactly one request creates the wallet").hasSize(1);
        assertThat(existingIds).as("all others are pointed at that same wallet")
                .hasSize(requests - 1)
                .containsOnly(createdIds.getFirst());
    }

    /** See TransferConcurrencyIntegrationTest: avoids the virtual-thread class-init deadlock. */
    private void warmUpCreatePathOnASingleThread() {
        CreateWalletCommand warmUp = new CreateWalletCommand("warm-up-" + UUID.randomUUID(), Currency.JPY);
        createWalletUseCase.createWallet(warmUp);
        createWalletUseCase.createWallet(warmUp);
    }
}
