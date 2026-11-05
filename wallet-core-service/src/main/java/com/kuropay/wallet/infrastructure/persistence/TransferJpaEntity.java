package com.kuropay.wallet.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Implements {@link Persistable} because the id is assigned by the application, not the database.
 * Without it Spring Data sees a non-null id, assumes the row already exists and calls
 * {@code merge()}, which costs an extra SELECT before every INSERT.
 */
@Entity
@Table(name = "transfers")
public class TransferJpaEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "source_wallet_id", nullable = false, updatable = false)
    private UUID sourceWalletId;

    @Column(name = "target_wallet_id", nullable = false, updatable = false)
    private UUID targetWalletId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, updatable = false, length = 32)
    private String status;

    @Column(name = "source_balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal sourceBalanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected TransferJpaEntity() {
    }

    public TransferJpaEntity(
            UUID id,
            String idempotencyKey,
            UUID sourceWalletId,
            UUID targetWalletId,
            BigDecimal amount,
            String currency,
            String status,
            BigDecimal sourceBalanceAfter,
            Instant createdAt
    ) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.sourceBalanceAfter = sourceBalanceAfter;
        this.createdAt = createdAt;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getSourceWalletId() {
        return sourceWalletId;
    }

    public UUID getTargetWalletId() {
        return targetWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getSourceBalanceAfter() {
        return sourceBalanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
