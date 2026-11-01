package com.kuropay.wallet.domain.event;

import com.kuropay.wallet.domain.model.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FundsTransferredEvent(
        UUID eventId,
        UUID transferId,
        UUID sourceWalletId,
        UUID targetWalletId,
        Money amount,
        String idempotencyKey,
        Instant occurredAt
) {
    public FundsTransferredEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
        Objects.requireNonNull(targetWalletId, "targetWalletId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
