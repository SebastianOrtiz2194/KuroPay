package com.kuropay.wallet.domain.port.out;

import com.kuropay.wallet.domain.exception.DuplicateWalletException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Wallet;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepositoryPort {

    Optional<Wallet> findById(UUID walletId);

    Optional<Wallet> findByIdForUpdate(UUID walletId);

    Optional<Wallet> findByOwnerIdAndCurrency(String ownerId, Currency currency);

    /**
     * Inserts a new wallet or updates the balance of an existing one.
     *
     * @throws DuplicateWalletException if inserting would give the owner a second wallet in the
     *                                  same currency
     */
    Wallet save(Wallet wallet);
}
