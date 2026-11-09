package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Transfer;
import com.kuropay.wallet.domain.model.TransferStatus;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.TransferRepositoryPort;
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
class TransferPersistenceAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private TransferRepositoryPort transferRepository;

    @Autowired
    private WalletRepositoryPort walletRepository;

    @Test
    void inserted_transfer_should_round_trip_through_the_database_unchanged() {
        Transfer transfer = newTransfer("idem-round-trip");

        transferRepository.insert(transfer);

        assertThat(transferRepository.findByIdempotencyKey("idem-round-trip")).contains(transfer);
    }

    @Test
    void reusing_an_idempotency_key_should_be_rejected_by_the_unique_constraint() {
        transferRepository.insert(newTransfer("idem-duplicate"));

        assertThatThrownBy(() -> transferRepository.insert(newTransfer("idem-duplicate")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("idem-duplicate");
    }

    private Transfer newTransfer(String idempotencyKey) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Wallet source = Wallet.createNew("user-adapter-source-" + UUID.randomUUID(), Currency.JPY, now);
        Wallet target = Wallet.createNew("user-adapter-target-" + UUID.randomUUID(), Currency.JPY, now);
        walletRepository.save(source);
        walletRepository.save(target);

        return new Transfer(
                UUID.randomUUID(), idempotencyKey, source.id(), target.id(),
                Money.of(1_000L, Currency.JPY), TransferStatus.COMPLETED,
                Money.of(9_000L, Currency.JPY), now
        );
    }
}
