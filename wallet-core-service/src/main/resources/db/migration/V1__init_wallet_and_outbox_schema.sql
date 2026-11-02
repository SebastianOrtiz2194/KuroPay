CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL CHECK (balance >= 0),
    currency VARCHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_wallets_owner_id ON wallets (owner_id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ
);

-- Partial index: only indexes PENDING rows so polling stays O(1) even with millions of historical rows
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
