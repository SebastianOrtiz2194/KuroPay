package com.kuropay.wallet.domain.port.out;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Deposit;
import java.util.Optional;

public interface DepositRepositoryPort {

    Optional<Deposit> findByIdempotencyKey(String idempotencyKey);

    /**
     * Deposits are append-only: there is no update operation on purpose.
     *
     * @throws DuplicateIdempotencyKeyException if another deposit already owns the key
     */
    void insert(Deposit deposit);
}
