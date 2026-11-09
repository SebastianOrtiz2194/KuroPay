package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Transfer;
import com.kuropay.wallet.domain.model.TransferStatus;
import com.kuropay.wallet.domain.port.out.TransferRepositoryPort;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class TransferPersistenceAdapter implements TransferRepositoryPort {

    static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_transfers_idempotency_key";

    private final SpringDataTransferRepository repository;

    public TransferPersistenceAdapter(SpringDataTransferRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey).map(TransferPersistenceAdapter::toDomain);
    }

    @Override
    public void insert(Transfer transfer) {
        try {
            // Flush now so a unique-constraint violation surfaces here, where we can translate it,
            // instead of later at commit time outside the adapter.
            repository.saveAndFlush(toEntity(transfer));
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw new DuplicateIdempotencyKeyException(transfer.idempotencyKey());
            }
            throw ex;
        }
    }

    private static TransferJpaEntity toEntity(Transfer transfer) {
        return new TransferJpaEntity(
                transfer.id(),
                transfer.idempotencyKey(),
                transfer.sourceWalletId(),
                transfer.targetWalletId(),
                transfer.amount().amount(),
                transfer.amount().currency().name(),
                transfer.status().name(),
                transfer.sourceBalanceAfter().amount(),
                transfer.createdAt()
        );
    }

    private static Transfer toDomain(TransferJpaEntity entity) {
        Currency currency = Currency.valueOf(entity.getCurrency());
        return new Transfer(
                entity.getId(),
                entity.getIdempotencyKey(),
                entity.getSourceWalletId(),
                entity.getTargetWalletId(),
                new Money(entity.getAmount(), currency),
                TransferStatus.valueOf(entity.getStatus()),
                new Money(entity.getSourceBalanceAfter(), currency),
                entity.getCreatedAt()
        );
    }
}
