package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.CreateWalletUseCase;
import com.kuropay.wallet.application.port.in.GetWalletQuery;
import com.kuropay.wallet.domain.exception.DuplicateWalletException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService implements CreateWalletUseCase, GetWalletQuery {

    private final WalletRepositoryPort walletRepository;
    private final Clock clock;

    public WalletService(WalletRepositoryPort walletRepository, Clock clock) {
        this.walletRepository = walletRepository;
        this.clock = clock;
    }

    /**
     * Insert first, ask later: the (owner, currency) unique constraint decides atomically, so two
     * concurrent creations can never both succeed. A check-then-insert would race.
     *
     * <p>Deliberately NOT @Transactional: in PostgreSQL a failed statement aborts the whole
     * transaction, so the lookup after a duplicate must run in a transaction of its own.
     */
    @Override
    public CreateWalletResult createWallet(CreateWalletCommand command) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        try {
            Wallet created = walletRepository.save(Wallet.createNew(command.ownerId(), command.currency(), now));
            return new CreateWalletResult.Created(created);
        } catch (DuplicateWalletException ex) {
            Wallet existing = walletRepository.findByOwnerIdAndCurrency(command.ownerId(), command.currency())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unique constraint fired but no wallet found for " + command, ex));
            return new CreateWalletResult.AlreadyExists(existing);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Wallet getWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
