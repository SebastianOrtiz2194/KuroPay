package com.kuropay.wallet.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDepositRepository extends JpaRepository<DepositJpaEntity, UUID> {

    Optional<DepositJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
