package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class WalletPersistenceAdapter implements WalletRepositoryPort {

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

        WalletJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
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
