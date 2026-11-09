-- Business rule: an owner has at most one wallet per currency. It also gives wallet creation
-- natural idempotency: a retried "create wallet" hits this constraint instead of duplicating.
ALTER TABLE wallets ADD CONSTRAINT uk_wallets_owner_currency UNIQUE (owner_id, currency);

-- The unique index on (owner_id, currency) also serves queries filtering by owner_id alone
-- (leftmost-prefix rule), so the old single-column index is now redundant write overhead.
DROP INDEX idx_wallets_owner_id;
