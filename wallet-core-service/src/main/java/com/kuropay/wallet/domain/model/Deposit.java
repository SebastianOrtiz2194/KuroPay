package com.kuropay.wallet.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of money entering the system from outside (a top-up). Like {@link Transfer},
 * rows are only ever inserted, so the table is both the audit trail and the durable store for
 * idempotency keys.
 *
 * <p>There is no status: a deposit has no business rejection (crediting cannot fail the way a
 * debit can), so a row existing means the wallet was credited. {@code balanceAfter} is the
 * wallet balance right after the credit, stored so a replay returns the original response even
 * if the balance changed since.
 */
public record Deposit(
        UUID id,
        String idempotencyKey,
        UUID walletId,
        Money amount,
        Money balanceAfter,
        Instant createdAt
) {
    public Deposit {
        Objects.requireNonNull(id, "id must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        Objects.requireNonNull(balanceAfter, "balanceAfter must not be null");
        amount.requireSameCurrency(balanceAfter);
        if (!balanceAfter.isGreaterThanOrEqual(amount)) {
            throw new IllegalArgumentException("balanceAfter cannot be lower than the deposited amount");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * An idempotency key identifies one specific request. Reusing it for a different request
     * (other wallet or amount) is a client bug that must be rejected, not silently replayed.
     */
    public boolean isSameRequestAs(UUID walletId, Money amount) {
        return this.walletId.equals(walletId) && this.amount.equals(amount);
    }

    /** Must be called after {@code wallet} has been credited. */
    public static Deposit completed(String idempotencyKey, Wallet wallet, Money amount, Instant now) {
        return new Deposit(UUID.randomUUID(), idempotencyKey, wallet.id(), amount, wallet.balance(), now);
    }
}
