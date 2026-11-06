package com.kuropay.wallet.application.port.in;

import com.kuropay.wallet.domain.model.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface TransferFundsUseCase {

    TransferResult transfer(TransferCommand command);

    record TransferCommand(
            UUID sourceWalletId,
            UUID targetWalletId,
            Money amount,
            String idempotencyKey
    ) {
        public TransferCommand {
            Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
            Objects.requireNonNull(targetWalletId, "targetWalletId must not be null");
            if (sourceWalletId.equals(targetWalletId)) {
                throw new IllegalArgumentException("Cannot transfer funds to the same wallet");
            }
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("Transfer amount must be positive");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey must not be blank");
            }
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "idempotencyKey must be at most %d characters".formatted(MAX_IDEMPOTENCY_KEY_LENGTH)
                );
            }
        }
    }

    /** Matches the transfers.idempotency_key column size. */
    int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    /**
     * {@code replayed} is true when the result comes from a previously stored transfer with the
     * same idempotency key instead of a fresh execution.
     */
    sealed interface TransferResult
            permits TransferResult.Completed, TransferResult.RejectedInsufficientFunds {

        boolean replayed();

        record Completed(
                UUID transferId,
                UUID sourceWalletId,
                UUID targetWalletId,
                Money transferredAmount,
                Money sourceNewBalance,
                Instant completedAt,
                boolean replayed
        ) implements TransferResult {
        }

        record RejectedInsufficientFunds(
                UUID sourceWalletId,
                Money availableBalance,
                Money requestedAmount,
                boolean replayed
        ) implements TransferResult {
        }
    }
}
