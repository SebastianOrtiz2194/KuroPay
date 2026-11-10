package com.kuropay.wallet.domain.model;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Test
    void completed_deposit_should_snapshot_the_balance_of_the_already_credited_wallet() {
        Wallet wallet = Wallet.createNew("user-1", Currency.JPY, NOW);
        Money amount = Money.of(1_000L, Currency.JPY);
        wallet.credit(amount, NOW);
        wallet.credit(amount, NOW);

        Deposit deposit = Deposit.completed("key-1", wallet, amount, NOW);

        assertThat(deposit.walletId()).isEqualTo(wallet.id());
        assertThat(deposit.amount()).isEqualTo(amount);
        assertThat(deposit.balanceAfter()).isEqualTo(Money.of(2_000L, Currency.JPY));
    }

    @Test
    void same_request_should_require_the_same_wallet_and_amount() {
        UUID walletId = UUID.randomUUID();
        Money amount = Money.of(1_000L, Currency.JPY);
        Deposit deposit = new Deposit(UUID.randomUUID(), "key-1", walletId, amount, amount, NOW);

        assertThat(deposit.isSameRequestAs(walletId, amount)).isTrue();
        assertThat(deposit.isSameRequestAs(UUID.randomUUID(), amount)).isFalse();
        assertThat(deposit.isSameRequestAs(walletId, Money.of(999L, Currency.JPY))).isFalse();
    }

    @Test
    void non_positive_amount_should_be_rejected() {
        Money zero = Money.zero(Currency.JPY);

        assertThatThrownBy(() -> new Deposit(UUID.randomUUID(), "key-1", UUID.randomUUID(), zero, zero, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void balance_after_lower_than_the_amount_should_be_rejected() {
        Money amount = Money.of(1_000L, Currency.JPY);
        Money lower = Money.of(500L, Currency.JPY);

        assertThatThrownBy(() -> new Deposit(UUID.randomUUID(), "key-1", UUID.randomUUID(), amount, lower, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balanceAfter");
    }
}
