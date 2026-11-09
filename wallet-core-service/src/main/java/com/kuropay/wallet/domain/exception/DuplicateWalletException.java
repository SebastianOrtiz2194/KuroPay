package com.kuropay.wallet.domain.exception;

import com.kuropay.wallet.domain.model.Currency;

/**
 * Raised by the persistence adapter when the (owner, currency) unique constraint fires: the owner
 * already has a wallet in that currency.
 */
public final class DuplicateWalletException extends RuntimeException {

    private final String ownerId;
    private final Currency currency;

    public DuplicateWalletException(String ownerId, Currency currency) {
        super("Owner '%s' already has a %s wallet".formatted(ownerId, currency));
        this.ownerId = ownerId;
        this.currency = currency;
    }

    public String ownerId() {
        return ownerId;
    }

    public Currency currency() {
        return currency;
    }
}
