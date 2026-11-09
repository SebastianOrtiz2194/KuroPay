package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.exception.DuplicateWalletException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class WalletPersistenceAdapter implements WalletRepositoryPort {

    static final String OWNER_CURRENCY_CONSTRAINT = "uk_wallets_owner_currency";

    private final SpringDataWalletRepository repository;

    public WalletPersistenceAdapter(SpringDataWalletRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Wallet> findById(UUID walletId) {
        return repository.findById(walletId).map(this::toDomain);
    }

    @Override
    public Optional<Wallet> findByIdForUpdate(UUID walletId) {
        return repository.findByIdForUpdate(walletId).map(this::toDomain);
    }

    @Override
    public Optional<Wallet> findByOwnerIdAndCurrency(String ownerId, Currency currency) {
        return repository.findByOwnerIdAndCurrency(ownerId, currency.name()).map(this::toDomain);
    }

    @Override
    public Wallet save(Wallet wallet) {
        WalletJpaEntity entity = repository.findById(wallet.id())
                .map(existing -> {
                    existing.setBalance(wallet.balance().amount());
                    existing.setUpdatedAt(wallet.updatedAt());
                    return existing;
                })
                .orElseGet(() -> new WalletJpaEntity(
                        wallet.id(),
                        wallet.ownerId(),
                        wallet.balance().amount(),
                        wallet.currency().name(),
                        wallet.version(),
                        wallet.createdAt(),
                        wallet.updatedAt()
                ));

        try {
            WalletJpaEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, OWNER_CURRENCY_CONSTRAINT)) {
                throw new DuplicateWalletException(wallet.ownerId(), wallet.currency());
            }
            throw ex;
        }
    }

    private Wallet toDomain(WalletJpaEntity entity) {
        Currency currency = Currency.valueOf(entity.getCurrency());
        Money balance = new Money(
                entity.getBalance().setScale(currency.fractionDigits()),
                currency
        );
        return new Wallet(
                entity.getId(),
                entity.getOwnerId(),
                balance,
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
