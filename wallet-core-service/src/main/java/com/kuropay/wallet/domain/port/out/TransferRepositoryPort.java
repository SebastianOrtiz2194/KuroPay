package com.kuropay.wallet.domain.port.out;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Transfer;
import java.util.Optional;

public interface TransferRepositoryPort {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Transfers are append-only: there is no update operation on purpose.
     *
     * @throws DuplicateIdempotencyKeyException if another transfer already owns the key
     */
    void insert(Transfer transfer);
}
