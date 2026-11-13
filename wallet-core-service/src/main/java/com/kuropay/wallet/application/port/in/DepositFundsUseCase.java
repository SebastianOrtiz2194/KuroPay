package com.kuropay.wallet.application.port.in;

import com.kuropay.wallet.domain.exception.DomainValidationException;
import com.kuropay.wallet.domain.model.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface DepositFundsUseCase {

    /** Matches the deposits.idempotency_key column size. */
    int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    DepositResult deposit(DepositCommand command);

    record DepositCommand(
            UUID walletId,
            Money amount,
            String idempotencyKey
    ) {
        public DepositCommand {
            Objects.requireNonNull(walletId, "walletId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.isPositive()) {
                throw new DomainValidationException("Deposit amount must be positive");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new DomainValidationException("idempotencyKey must not be blank");
            }
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new DomainValidationException(
                        "idempotencyKey must be at most %d characters".formatted(MAX_IDEMPOTENCY_KEY_LENGTH)
                );
            }
        }
    }

    /**
     * A plain record, not a sealed hierarchy like the transfer result: a deposit has no business
     * rejection, so there is only one possible outcome. {@code replayed} is true when the result
     * comes from a previously stored deposit with the same idempotency key.
     */
    record DepositResult(
            UUID depositId,
            UUID walletId,
            Money depositedAmount,
            Money newBalance,
            Instant completedAt,
            boolean replayed
    ) {
    }
}
