package com.kuropay.wallet.domain.exception;

import java.util.UUID;

public final class WalletNotFoundException extends RuntimeException {

    private final UUID walletId;

    public WalletNotFoundException(UUID walletId) {
        super("Wallet not found: " + walletId);
        this.walletId = walletId;
    }

    public UUID walletId() {
        return walletId;
    }
}
