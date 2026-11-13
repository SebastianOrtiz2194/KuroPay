package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.DepositFundsUseCase;
import com.kuropay.wallet.domain.event.FundsDepositedEvent;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Deposit;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.DepositRepositoryPort;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositFundsService implements DepositFundsUseCase {

    private final WalletRepositoryPort walletRepository;
    private final DepositRepositoryPort depositRepository;
    private final OutboxEventPort outboxEventPort;
    private final Clock clock;

    public DepositFundsService(
            WalletRepositoryPort walletRepository,
            DepositRepositoryPort depositRepository,
            OutboxEventPort outboxEventPort,
            Clock clock
    ) {
        this.walletRepository = walletRepository;
        this.depositRepository = depositRepository;
        this.outboxEventPort = outboxEventPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DepositResult deposit(DepositCommand command) {
        // A credit can never overdraw, but the lock is still needed: the balance is read, changed
        // in memory and written back, so two unlocked deposits would overwrite each other
        // (lost update). It also makes same-key retries queue up behind the first one.
        Wallet wallet = walletRepository.findByIdForUpdate(command.walletId())
                .orElseThrow(() -> new WalletNotFoundException(command.walletId()));

        // Looked up only AFTER the lock is held, for the same reason as in TransferFundsService:
        // a concurrent retry waits on the lock and then sees the committed deposit (READ COMMITTED).
        Optional<Deposit> previous = depositRepository.findByIdempotencyKey(command.idempotencyKey());
        if (previous.isPresent()) {
            Deposit stored = previous.get();
            if (!stored.isSameRequestAs(command.walletId(), command.amount())) {
                throw new DuplicateIdempotencyKeyException(command.idempotencyKey());
            }
            return toResult(stored, true);
        }

        // PostgreSQL stores microseconds; truncating keeps the fresh and replayed responses identical.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        wallet.credit(command.amount(), now);
        walletRepository.save(wallet);

        // The same key sent for a DIFFERENT wallet takes a different lock, so it can get past the
        // lookup above. The unique constraint stops it here and the whole transaction, including
        // the credit, rolls back.
        Deposit completed = Deposit.completed(command.idempotencyKey(), wallet, command.amount(), now);
        depositRepository.insert(completed);

        outboxEventPort.publish(new FundsDepositedEvent(
                UUID.randomUUID(),
                completed.id(),
                completed.walletId(),
                completed.amount(),
                completed.idempotencyKey(),
                completed.createdAt()
        ));

        return toResult(completed, false);
    }

    /** Single mapping used for both fresh and replayed deposits, so both responses are identical. */
    private static DepositResult toResult(Deposit deposit, boolean replayed) {
        return new DepositResult(
                deposit.id(),
                deposit.walletId(),
                deposit.amount(),
                deposit.balanceAfter(),
                deposit.createdAt(),
                replayed
        );
    }
}
