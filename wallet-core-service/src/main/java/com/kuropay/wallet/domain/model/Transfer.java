package com.kuropay.wallet.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a transfer attempt. Rows are only ever inserted, never updated, so the
 * table doubles as an audit trail and as the durable store for idempotency keys.
 *
 * <p>{@code sourceBalanceAfter} is the source wallet balance right after this attempt: the
 * debited balance for a completed transfer, the untouched balance for a rejected one. Storing
 * it lets a replay return exactly the original response even if the balance changed since.
 */
public record Transfer(
        UUID id,
        String idempotencyKey,
        UUID sourceWalletId,
        UUID targetWalletId,
        Money amount,
        TransferStatus status,
        Money sourceBalanceAfter,
        Instant createdAt
) {
    public Transfer {
        Objects.requireNonNull(id, "id must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
        Objects.requireNonNull(targetWalletId, "targetWalletId must not be null");
        if (sourceWalletId.equals(targetWalletId)) {
            throw new IllegalArgumentException("source and target wallets must differ");
        }
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(sourceBalanceAfter, "sourceBalanceAfter must not be null");
        amount.requireSameCurrency(sourceBalanceAfter);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * An idempotency key identifies one specific request. Reusing it for a different request
     * (other wallets or amount) is a client bug that must be rejected, not silently replayed.
     */
    public boolean isSameRequestAs(UUID sourceWalletId, UUID targetWalletId, Money amount) {
        return this.sourceWalletId.equals(sourceWalletId)
                && this.targetWalletId.equals(targetWalletId)
                && this.amount.equals(amount);
    }

    /** Must be called after {@code source} has been debited. */
    public static Transfer completed(
            String idempotencyKey, Wallet source, Wallet target, Money amount, Instant now
    ) {
        return new Transfer(
                UUID.randomUUID(), idempotencyKey, source.id(), target.id(),
                amount, TransferStatus.COMPLETED, source.balance(), now
        );
    }

    public static Transfer rejectedForInsufficientFunds(
            String idempotencyKey, Wallet source, Wallet target, Money amount, Instant now
    ) {
        return new Transfer(
                UUID.randomUUID(), idempotencyKey, source.id(), target.id(),
                amount, TransferStatus.REJECTED_INSUFFICIENT_FUNDS, source.balance(), now
        );
    }
}
