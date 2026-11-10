package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Deposit;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.DepositRepositoryPort;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
class DepositPersistenceAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DepositRepositoryPort depositRepository;

    @Autowired
    private WalletRepositoryPort walletRepository;

    @Test
    void inserted_deposit_should_round_trip_through_the_database_unchanged() {
        Deposit deposit = newDeposit("dep-round-trip");

        depositRepository.insert(deposit);

        assertThat(depositRepository.findByIdempotencyKey("dep-round-trip")).contains(deposit);
    }

    @Test
    void reusing_an_idempotency_key_should_be_rejected_by_the_unique_constraint() {
        depositRepository.insert(newDeposit("dep-duplicate"));

        assertThatThrownBy(() -> depositRepository.insert(newDeposit("dep-duplicate")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("dep-duplicate");
    }

    private Deposit newDeposit(String idempotencyKey) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Wallet wallet = Wallet.createNew("user-adapter-deposit-" + UUID.randomUUID(), Currency.JPY, now);
        walletRepository.save(wallet);

        return new Deposit(
                UUID.randomUUID(), idempotencyKey, wallet.id(),
                Money.of(1_000L, Currency.JPY), Money.of(1_000L, Currency.JPY), now
        );
    }
}
