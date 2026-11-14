package com.kuropay.wallet.infrastructure.idempotency;

/**
 * Namespace of an idempotency key inside the cache. PostgreSQL keeps one UNIQUE constraint per
 * table (transfers, deposits), so the same key is legal once per operation. The cache has to
 * follow that same rule, otherwise it would answer differently from the database.
 */
public enum IdempotentOperation {

    TRANSFER("transfer"),
    DEPOSIT("deposit");

    /** Explicit instead of {@code name()}: renaming a constant must not silently move the Redis keys. */
    private final String keySegment;

    IdempotentOperation(String keySegment) {
        this.keySegment = keySegment;
    }

    String keySegment() {
        return keySegment;
    }
}
