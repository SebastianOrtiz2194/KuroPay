package com.kuropay.wallet.application.port.in;

import com.kuropay.wallet.domain.exception.DomainValidationException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Wallet;
import java.util.Objects;

public interface CreateWalletUseCase {

    /** Matches the wallets.owner_id column size. */
    int MAX_OWNER_ID_LENGTH = 128;

    CreateWalletResult createWallet(CreateWalletCommand command);

    record CreateWalletCommand(String ownerId, Currency currency) {
        public CreateWalletCommand {
            if (ownerId == null || ownerId.isBlank()) {
                throw new DomainValidationException("ownerId must not be blank");
            }
            if (ownerId.length() > MAX_OWNER_ID_LENGTH) {
                throw new DomainValidationException(
                        "ownerId must be at most %d characters".formatted(MAX_OWNER_ID_LENGTH)
                );
            }
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }

    /**
     * An owner already having a wallet in that currency is an expected business outcome (for
     * example a retried request), not a failure, so it is a result rather than an exception.
     */
    sealed interface CreateWalletResult
            permits CreateWalletResult.Created, CreateWalletResult.AlreadyExists {

        record Created(Wallet wallet) implements CreateWalletResult {
        }

        record AlreadyExists(Wallet existing) implements CreateWalletResult {
        }
    }
}
