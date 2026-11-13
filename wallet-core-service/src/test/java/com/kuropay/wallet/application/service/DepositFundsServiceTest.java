package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositCommand;
import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositResult;
import com.kuropay.wallet.domain.event.FundsDepositedEvent;
import com.kuropay.wallet.domain.exception.CurrencyMismatchException;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Deposit;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.DepositRepositoryPort;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepositFundsServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private WalletRepositoryPort walletRepository;

    @Mock
    private DepositRepositoryPort depositRepository;

    @Mock
    private OutboxEventPort outboxEventPort;

    private DepositFundsService depositFundsService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        depositFundsService = new DepositFundsService(walletRepository, depositRepository, outboxEventPort, fixedClock);
    }

    @Test
    void should_credit_wallet_store_deposit_and_publish_outbox_event() {
        Wallet wallet = walletWithBalance(2_000L);
        when(walletRepository.findByIdForUpdate(WALLET_ID)).thenReturn(Optional.of(wallet));

        DepositResult result = depositFundsService.deposit(
                new DepositCommand(WALLET_ID, Money.of(3_000L, Currency.JPY), "dep-key-001")
        );

        ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
        verify(depositRepository).insert(depositCaptor.capture());
        Deposit stored = depositCaptor.getValue();
        assertThat(stored.idempotencyKey()).isEqualTo("dep-key-001");
        assertThat(stored.balanceAfter()).isEqualTo(Money.of(5_000L, Currency.JPY));

        assertThat(result).isEqualTo(new DepositResult(
                stored.id(), WALLET_ID,
                Money.of(3_000L, Currency.JPY), Money.of(5_000L, Currency.JPY), FIXED_NOW,
                false
        ));
        assertThat(wallet.balance()).isEqualTo(Money.of(5_000L, Currency.JPY));
        verify(walletRepository).save(wallet);

        ArgumentCaptor<FundsDepositedEvent> eventCaptor = ArgumentCaptor.forClass(FundsDepositedEvent.class);
        verify(outboxEventPort).publish(eventCaptor.capture());
        FundsDepositedEvent event = eventCaptor.getValue();
        assertThat(event.depositId()).isEqualTo(stored.id());
        assertThat(event.walletId()).isEqualTo(WALLET_ID);
        assertThat(event.amount()).isEqualTo(Money.of(3_000L, Currency.JPY));
        assertThat(event.idempotencyKey()).isEqualTo("dep-key-001");
    }

    @Test
    void should_replay_stored_deposit_without_crediting_again_when_idempotency_key_was_already_used() {
        // Balance as it is now: the original deposit ran and the money was spent afterwards.
        Wallet wallet = walletWithBalance(500L);
        when(walletRepository.findByIdForUpdate(WALLET_ID)).thenReturn(Optional.of(wallet));

        Instant originalTime = FIXED_NOW.minusSeconds(30);
        Deposit original = new Deposit(
                UUID.randomUUID(), "dep-key-002", WALLET_ID,
                Money.of(3_000L, Currency.JPY), Money.of(5_000L, Currency.JPY), originalTime
        );
        when(depositRepository.findByIdempotencyKey("dep-key-002")).thenReturn(Optional.of(original));

        DepositResult result = depositFundsService.deposit(
                new DepositCommand(WALLET_ID, Money.of(3_000L, Currency.JPY), "dep-key-002")
        );

        // The replay answers with the balance of the original response, not the current one.
        assertThat(result).isEqualTo(new DepositResult(
                original.id(), WALLET_ID,
                Money.of(3_000L, Currency.JPY), Money.of(5_000L, Currency.JPY), originalTime,
                true
        ));
        assertThat(wallet.balance()).isEqualTo(Money.of(500L, Currency.JPY));
        verify(walletRepository, never()).save(any());
        verify(depositRepository, never()).insert(any());
        verify(outboxEventPort, never()).publish(any());

        // The key must be checked only once the wallet lock is held (see DepositFundsService).
        InOrder order = inOrder(walletRepository, depositRepository);
        order.verify(walletRepository).findByIdForUpdate(WALLET_ID);
        order.verify(depositRepository).findByIdempotencyKey("dep-key-002");
    }

    @Test
    void should_reject_reused_idempotency_key_when_the_request_differs_from_the_stored_one() {
        Wallet wallet = walletWithBalance(5_000L);
        when(walletRepository.findByIdForUpdate(WALLET_ID)).thenReturn(Optional.of(wallet));

        Deposit original = new Deposit(
                UUID.randomUUID(), "dep-key-003", WALLET_ID,
                Money.of(3_000L, Currency.JPY), Money.of(5_000L, Currency.JPY), FIXED_NOW
        );
        when(depositRepository.findByIdempotencyKey("dep-key-003")).thenReturn(Optional.of(original));

        // Same key, different amount: a client bug that must not be answered with the old result.
        DepositCommand differentAmount = new DepositCommand(WALLET_ID, Money.of(9_000L, Currency.JPY), "dep-key-003");

        assertThatThrownBy(() -> depositFundsService.deposit(differentAmount))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
        assertThat(wallet.balance()).isEqualTo(Money.of(5_000L, Currency.JPY));
        verify(walletRepository, never()).save(any());
        verify(depositRepository, never()).insert(any());
        verify(outboxEventPort, never()).publish(any());
    }

    @Test
    void should_fail_without_touching_anything_when_wallet_does_not_exist() {
        when(walletRepository.findByIdForUpdate(WALLET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> depositFundsService.deposit(
                new DepositCommand(WALLET_ID, Money.of(3_000L, Currency.JPY), "dep-key-004")
        )).isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(depositRepository, outboxEventPort);
    }

    @Test
    void should_not_store_anything_when_deposit_currency_differs_from_wallet_currency() {
        Wallet jpyWallet = walletWithBalance(2_000L);
        when(walletRepository.findByIdForUpdate(WALLET_ID)).thenReturn(Optional.of(jpyWallet));

        assertThatThrownBy(() -> depositFundsService.deposit(
                new DepositCommand(WALLET_ID, Money.of(10L, Currency.USD), "dep-key-005")
        )).isInstanceOf(CurrencyMismatchException.class);

        assertThat(jpyWallet.balance()).isEqualTo(Money.of(2_000L, Currency.JPY));
        verify(walletRepository, never()).save(any());
        verify(depositRepository, never()).insert(any());
        verify(outboxEventPort, never()).publish(any());
    }

    private static Wallet walletWithBalance(long jpyAmount) {
        return new Wallet(WALLET_ID, "user-deposit", Money.of(jpyAmount, Currency.JPY), 0L, FIXED_NOW, FIXED_NOW);
    }
}
