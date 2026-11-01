package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase;
import com.kuropay.wallet.domain.event.FundsTransferredEvent;
import com.kuropay.wallet.domain.exception.InsufficientFundsException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransferFundsService implements TransferFundsUseCase {

    private final WalletRepositoryPort walletRepository;
    private final OutboxEventPort outboxEventPort;
    private final Clock clock;

    public TransferFundsService(
            WalletRepositoryPort walletRepository,
            OutboxEventPort outboxEventPort,
            Clock clock
    ) {
        this.walletRepository = walletRepository;
        this.outboxEventPort = outboxEventPort;
        this.clock = clock;
    }

    @Override
    public TransferResult transfer(TransferCommand command) {
        UUID firstLockId = compareUnsigned(command.sourceWalletId(), command.targetWalletId()) < 0
                ? command.sourceWalletId()
                : command.targetWalletId();
        UUID secondLockId = firstLockId.equals(command.sourceWalletId())
                ? command.targetWalletId()
                : command.sourceWalletId();

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new WalletNotFoundException(firstLockId));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new WalletNotFoundException(secondLockId));

        Wallet sourceWallet = firstLockId.equals(command.sourceWalletId()) ? firstLocked : secondLocked;
        Wallet targetWallet = firstLockId.equals(command.targetWalletId()) ? firstLocked : secondLocked;

        Instant now = Instant.now(clock);
        try {
            sourceWallet.debit(command.amount(), now);
        } catch (InsufficientFundsException ex) {
            return new TransferResult.RejectedInsufficientFunds(
                    ex.walletId(),
                    ex.currentBalance(),
                    ex.requestedDebit()
            );
        }

        targetWallet.credit(command.amount(), now);

        walletRepository.save(sourceWallet);
        walletRepository.save(targetWallet);

        UUID transferId = UUID.randomUUID();
        outboxEventPort.publish(new FundsTransferredEvent(
                UUID.randomUUID(),
                transferId,
                sourceWallet.id(),
                targetWallet.id(),
                command.amount(),
                command.idempotencyKey(),
                now
        ));

        return new TransferResult.Completed(
                transferId,
                sourceWallet.id(),
                targetWallet.id(),
                command.amount(),
                sourceWallet.balance(),
                now
        );
    }

    private static int compareUnsigned(UUID left, UUID right) {
        int mostSig = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSig != 0
                ? mostSig
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
