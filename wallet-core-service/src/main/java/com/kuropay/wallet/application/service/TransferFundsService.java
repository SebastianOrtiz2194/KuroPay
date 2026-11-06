package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase;
import com.kuropay.wallet.domain.event.FundsTransferredEvent;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.exception.InsufficientFundsException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Transfer;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
import com.kuropay.wallet.domain.port.out.TransferRepositoryPort;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferFundsService implements TransferFundsUseCase {

    private final WalletRepositoryPort walletRepository;
    private final TransferRepositoryPort transferRepository;
    private final OutboxEventPort outboxEventPort;
    private final Clock clock;

    public TransferFundsService(
            WalletRepositoryPort walletRepository,
            TransferRepositoryPort transferRepository,
            OutboxEventPort outboxEventPort,
            Clock clock
    ) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.outboxEventPort = outboxEventPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TransferResult transfer(TransferCommand command) {
        LockedWallets wallets = lockInDeterministicOrder(command.sourceWalletId(), command.targetWalletId());

        // The key is looked up only AFTER the wallet locks are held. A concurrent retry with the
        // same key blocks on those locks until the first transaction commits; under PostgreSQL's
        // READ COMMITTED isolation this query then sees the committed transfer and we replay it.
        Optional<Transfer> previous = transferRepository.findByIdempotencyKey(command.idempotencyKey());
        if (previous.isPresent()) {
            Transfer stored = previous.get();
            if (!stored.isSameRequestAs(command.sourceWalletId(), command.targetWalletId(), command.amount())) {
                throw new DuplicateIdempotencyKeyException(command.idempotencyKey());
            }
            return toResult(stored, true);
        }

        // PostgreSQL stores microseconds; truncating keeps the fresh and replayed responses identical.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Wallet source = wallets.source();
        Wallet target = wallets.target();

        try {
            source.debit(command.amount(), now);
        } catch (InsufficientFundsException ex) {
            Transfer rejected = Transfer.rejectedForInsufficientFunds(
                    command.idempotencyKey(), source, target, command.amount(), now
            );
            transferRepository.insert(rejected);
            return toResult(rejected, false);
        }
        target.credit(command.amount(), now);

        walletRepository.save(source);
        walletRepository.save(target);

        Transfer completed = Transfer.completed(command.idempotencyKey(), source, target, command.amount(), now);
        transferRepository.insert(completed);

        outboxEventPort.publish(new FundsTransferredEvent(
                UUID.randomUUID(),
                completed.id(),
                completed.sourceWalletId(),
                completed.targetWalletId(),
                completed.amount(),
                completed.idempotencyKey(),
                completed.createdAt()
        ));

        return toResult(completed, false);
    }

    /** Always locks the lower UUID first so two opposite transfers can never deadlock. */
    private LockedWallets lockInDeterministicOrder(UUID sourceId, UUID targetId) {
        boolean sourceFirst = compareUnsigned(sourceId, targetId) < 0;
        UUID firstId = sourceFirst ? sourceId : targetId;
        UUID secondId = sourceFirst ? targetId : sourceId;

        Wallet first = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletNotFoundException(firstId));
        Wallet second = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletNotFoundException(secondId));

        return sourceFirst ? new LockedWallets(first, second) : new LockedWallets(second, first);
    }

    /** Single mapping used for both fresh and replayed transfers, so both responses are identical. */
    private static TransferResult toResult(Transfer transfer, boolean replayed) {
        return switch (transfer.status()) {
            case COMPLETED -> new TransferResult.Completed(
                    transfer.id(),
                    transfer.sourceWalletId(),
                    transfer.targetWalletId(),
                    transfer.amount(),
                    transfer.sourceBalanceAfter(),
                    transfer.createdAt(),
                    replayed
            );
            case REJECTED_INSUFFICIENT_FUNDS -> new TransferResult.RejectedInsufficientFunds(
                    transfer.sourceWalletId(),
                    transfer.sourceBalanceAfter(),
                    transfer.amount(),
                    replayed
            );
        };
    }

    /** PostgreSQL orders UUIDs byte-wise (unsigned); UUID.compareTo is signed, so it would disagree. */
    private static int compareUnsigned(UUID left, UUID right) {
        int mostSig = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSig != 0
                ? mostSig
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private record LockedWallets(Wallet source, Wallet target) {
    }
}
