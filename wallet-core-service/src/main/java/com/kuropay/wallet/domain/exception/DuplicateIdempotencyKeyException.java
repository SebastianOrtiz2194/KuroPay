package com.kuropay.wallet.domain.exception;

/**
 * Thrown when an idempotency key is reused for a different request. Detected either by comparing
 * the stored transfer with the incoming request, or by the database unique constraint when two
 * different requests with the same key race each other.
 */
public final class DuplicateIdempotencyKeyException extends RuntimeException {

    private final String idempotencyKey;

    public DuplicateIdempotencyKeyException(String idempotencyKey) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
