package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Deposit;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.port.out.DepositRepositoryPort;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class DepositPersistenceAdapter implements DepositRepositoryPort {

    static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_deposits_idempotency_key";

    private final SpringDataDepositRepository repository;

    public DepositPersistenceAdapter(SpringDataDepositRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Deposit> findByIdempotencyKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey).map(DepositPersistenceAdapter::toDomain);
    }

    @Override
    public void insert(Deposit deposit) {
        try {
            // Flush now so a unique-constraint violation surfaces here, where we can translate it,
            // instead of later at commit time outside the adapter.
            repository.saveAndFlush(toEntity(deposit));
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw new DuplicateIdempotencyKeyException(deposit.idempotencyKey());
            }
            throw ex;
        }
    }

    private static DepositJpaEntity toEntity(Deposit deposit) {
        return new DepositJpaEntity(
                deposit.id(),
                deposit.idempotencyKey(),
                deposit.walletId(),
                deposit.amount().amount(),
                deposit.amount().currency().name(),
                deposit.balanceAfter().amount(),
                deposit.createdAt()
        );
    }

    private static Deposit toDomain(DepositJpaEntity entity) {
        Currency currency = Currency.valueOf(entity.getCurrency());
        return new Deposit(
                entity.getId(),
                entity.getIdempotencyKey(),
                entity.getWalletId(),
                new Money(entity.getAmount(), currency),
                new Money(entity.getBalanceAfter(), currency),
                entity.getCreatedAt()
        );
    }
}
