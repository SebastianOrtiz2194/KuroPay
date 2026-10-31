package com.kuropay.wallet.domain.model;

import com.kuropay.wallet.domain.exception.InsufficientFundsException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Wallet {

    private final UUID id;
    private final String ownerId;
    private Money balance;
    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;

    public Wallet(
            UUID id,
            String ownerId,
            Money balance,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        this.ownerId = ownerId;
        Objects.requireNonNull(balance, "balance must not be null");
        if (balance.isNegative()) {
            throw new IllegalArgumentException("Wallet balance cannot be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        this.balance = balance;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Wallet createNew(String ownerId, Currency currency, Instant now) {
        return new Wallet(
                UUID.randomUUID(),
                ownerId,
                Money.zero(currency),
                0L,
                now,
                now
        );
    }

    public void credit(Money amount, Instant now) {
        requirePositiveOperationAmount(amount);
        this.balance = this.balance.add(amount);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void debit(Money amount, Instant now) {
        requirePositiveOperationAmount(amount);
        if (!this.balance.isGreaterThanOrEqual(amount)) {
            throw new InsufficientFundsException(this.id, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private void requirePositiveOperationAmount(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        this.balance.requireSameCurrency(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Operation amount must be strictly positive");
        }
    }

    public UUID id() {
        return id;
    }

    public String ownerId() {
        return ownerId;
    }

    public Money balance() {
        return balance;
    }

    public Currency currency() {
        return balance.currency();
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
