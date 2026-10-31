package com.kuropay.wallet.domain.model;

import com.kuropay.wallet.domain.exception.InsufficientFundsException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Test
    void jpy_money_should_reject_fractional_decimals_while_usd_allows_cents() {
        Money jpy = Money.of(10_000L, Currency.JPY);
        Money usd = Money.of("49.99", Currency.USD);

        assertThat(jpy.amount().toPlainString()).isEqualTo("10000");
        assertThat(usd.amount().toPlainString()).isEqualTo("49.99");

        assertThatThrownBy(() -> Money.of("100.50", Currency.JPY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds allowed decimal places (0) for currency JPY");
    }

    @Test
    void wallet_should_credit_and_debit_valid_amounts() {
        Wallet wallet = Wallet.createNew("user-tokyo-01", Currency.JPY, NOW);

        wallet.credit(Money.of(15_000L, Currency.JPY), NOW.plusSeconds(10));
        wallet.debit(Money.of(4_500L, Currency.JPY), NOW.plusSeconds(20));

        assertThat(wallet.balance()).isEqualTo(Money.of(10_500L, Currency.JPY));
        assertThat(wallet.updatedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void wallet_should_throw_insufficient_funds_when_debit_exceeds_balance() {
        Wallet wallet = Wallet.createNew("user-tokyo-01", Currency.JPY, NOW);
        wallet.credit(Money.of(5_000L, Currency.JPY), NOW);

        assertThatThrownBy(() -> wallet.debit(Money.of(5_001L, Currency.JPY), NOW.plusSeconds(5)))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");

        assertThat(wallet.balance()).isEqualTo(Money.of(5_000L, Currency.JPY));
    }

    @Test
    void wallet_should_reject_currency_mismatch_operations() {
        Wallet jpyWallet = Wallet.createNew("user-tokyo-01", Currency.JPY, NOW);

        assertThatThrownBy(() -> jpyWallet.credit(Money.of("10.00", Currency.USD), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }
}
