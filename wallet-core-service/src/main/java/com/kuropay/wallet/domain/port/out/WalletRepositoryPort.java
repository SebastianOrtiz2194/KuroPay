package com.kuropay.wallet.domain.port.out;

import com.kuropay.wallet.domain.model.Wallet;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepositoryPort {

    Optional<Wallet> findById(UUID walletId);

    Optional<Wallet> findByIdForUpdate(UUID walletId);

    Wallet save(Wallet wallet);
}
