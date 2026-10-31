package com.kuropay.wallet.domain.exception;

import com.kuropay.wallet.domain.model.Money;
import java.util.UUID;

public final class InsufficientFundsException extends RuntimeException {

    private final UUID walletId;
    private final Money currentBalance;
    private final Money requestedDebit;

    public InsufficientFundsException(UUID walletId, Money currentBalance, Money requestedDebit) {
        super("Wallet %s has insufficient funds: balance=%s %s, requested=%s %s".formatted(
                walletId,
                currentBalance.amount().toPlainString(),
                currentBalance.currency(),
                requestedDebit.amount().toPlainString(),
                requestedDebit.currency()
        ));
        this.walletId = walletId;
        this.currentBalance = currentBalance;
        this.requestedDebit = requestedDebit;
    }

    public UUID walletId() {
        return walletId;
    }

    public Money currentBalance() {
        return currentBalance;
    }

    public Money requestedDebit() {
        return requestedDebit;
    }
}
