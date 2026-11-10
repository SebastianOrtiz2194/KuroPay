-- Append-only record of every deposit (money entering the system from outside).
-- Deposits get their own table instead of a nullable source_wallet_id on transfers: every column
-- stays NOT NULL and the transfers CHECK constraints keep meaning exactly what they say.
-- The UNIQUE constraint on idempotency_key is the real duplicate-credit guarantee:
-- it is checked inside the same transaction that credits the wallet.
CREATE TABLE deposits (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(255)   NOT NULL,
    wallet_id       UUID           NOT NULL REFERENCES wallets (id),
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)     NOT NULL,
    balance_after   NUMERIC(19, 4) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_deposits_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_deposits_balance_after CHECK (balance_after >= amount)
);

-- PostgreSQL does not index foreign keys automatically; this also serves wallet history queries.
CREATE INDEX idx_deposits_wallet ON deposits (wallet_id, created_at);
