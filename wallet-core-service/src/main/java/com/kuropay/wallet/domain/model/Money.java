package com.kuropay.wallet.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try {
            amount = amount.setScale(currency.fractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Amount %s exceeds allowed decimal places (%d) for currency %s"
                            .formatted(amount.toPlainString(), currency.fractionDigits(), currency),
                    ex
            );
        }
    }

    public static Money of(long majorUnits, Currency currency) {
        return new Money(BigDecimal.valueOf(majorUnits), currency);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return of(0L, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) >= 0;
    }

    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money must not be null");
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: cannot operate between %s and %s".formatted(this.currency, other.currency)
            );
        }
    }
}
