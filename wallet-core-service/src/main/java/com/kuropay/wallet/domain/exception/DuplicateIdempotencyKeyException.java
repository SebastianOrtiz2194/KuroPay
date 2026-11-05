package com.kuropay.wallet.domain.exception;

/**
 * Thrown when a transfer cannot be recorded because another transfer already owns the same
 * idempotency key. Raised by the persistence adapter when the database unique constraint fires.
 */
public final class DuplicateIdempotencyKeyException extends RuntimeException {

    private final String idempotencyKey;

    public DuplicateIdempotencyKeyException(String idempotencyKey) {
        super("Idempotency-Key '%s' has already been used by another transfer".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
