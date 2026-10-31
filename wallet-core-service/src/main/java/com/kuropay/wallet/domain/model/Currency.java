package com.kuropay.wallet.domain.model;

public enum Currency {
    JPY(0),
    USD(2),
    EUR(2);

    private final int fractionDigits;

    Currency(int fractionDigits) {
        this.fractionDigits = fractionDigits;
    }

    public int fractionDigits() {
        return fractionDigits;
    }
}
