package com.kuropay.wallet.infrastructure.idempotency;

public final class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("A request with Idempotency-Key '%s' is currently being processed".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
