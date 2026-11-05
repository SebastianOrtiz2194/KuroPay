-- Append-only record of every transfer attempt (completed or rejected).
-- The UNIQUE constraint on idempotency_key is the real duplicate-payment guarantee:
-- it is checked inside the same transaction that moves the money.
CREATE TABLE transfers (
    id                   UUID PRIMARY KEY,
    idempotency_key      VARCHAR(255)   NOT NULL,
    source_wallet_id     UUID           NOT NULL REFERENCES wallets (id),
    target_wallet_id     UUID           NOT NULL REFERENCES wallets (id),
    amount               NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3)     NOT NULL,
    status               VARCHAR(32)    NOT NULL
        CHECK (status IN ('COMPLETED', 'REJECTED_INSUFFICIENT_FUNDS')),
    source_balance_after NUMERIC(19, 4) NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_transfers_distinct_wallets CHECK (source_wallet_id <> target_wallet_id)
);

-- PostgreSQL does not index foreign keys automatically; these also serve wallet history queries.
CREATE INDEX idx_transfers_source_wallet ON transfers (source_wallet_id, created_at);
CREATE INDEX idx_transfers_target_wallet ON transfers (target_wallet_id, created_at);
