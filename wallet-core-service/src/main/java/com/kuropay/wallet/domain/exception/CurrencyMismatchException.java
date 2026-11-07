package com.kuropay.wallet.domain.exception;

import com.kuropay.wallet.domain.model.Currency;

/**
 * A well-formed amount whose currency conflicts with the one it is combined with (typically the
 * wallet's currency). Unlike {@link DomainValidationException} the request itself is valid; it
 * just cannot be applied to this wallet, hence a separate type and HTTP status (422).
 */
public final class CurrencyMismatchException extends IllegalArgumentException {

    private final Currency expected;
    private final Currency actual;

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: cannot operate between %s and %s".formatted(expected, actual));
        this.expected = expected;
        this.actual = actual;
    }

    public Currency expected() {
        return expected;
    }

    public Currency actual() {
        return actual;
    }
}
