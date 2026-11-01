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
        }
    }

    sealed interface TransferResult
            permits TransferResult.Completed, TransferResult.RejectedInsufficientFunds {

        record Completed(
                UUID transferId,
                UUID sourceWalletId,
                UUID targetWalletId,
                Money transferredAmount,
                Money sourceNewBalance,
                Instant completedAt
        ) implements TransferResult {
        }

        record RejectedInsufficientFunds(
                UUID sourceWalletId,
                Money availableBalance,
                Money requestedAmount
        ) implements TransferResult {
        }
    }
}
