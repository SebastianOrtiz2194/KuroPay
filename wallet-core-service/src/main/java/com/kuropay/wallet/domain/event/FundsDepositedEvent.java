package com.kuropay.wallet.domain.event;

import com.kuropay.wallet.domain.model.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FundsDepositedEvent(
        UUID eventId,
        UUID depositId,
        UUID walletId,
        Money amount,
        String idempotencyKey,
        Instant occurredAt
) implements WalletEvent {
    public FundsDepositedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(depositId, "depositId must not be null");
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
