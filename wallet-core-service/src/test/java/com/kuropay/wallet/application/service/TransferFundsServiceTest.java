package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferCommand;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferResult;
import com.kuropay.wallet.domain.event.FundsTransferredEvent;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Transfer;
import com.kuropay.wallet.domain.model.TransferStatus;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
import com.kuropay.wallet.domain.port.out.TransferRepositoryPort;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferFundsServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-31T12:00:00Z");

    @Mock
    private WalletRepositoryPort walletRepository;

    @Mock
    private TransferRepositoryPort transferRepository;

    @Mock
    private OutboxEventPort outboxEventPort;

    private TransferFundsService transferFundsService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        transferFundsService = new TransferFundsService(walletRepository, transferRepository, outboxEventPort, fixedClock);
    }

    @Test
    void should_lock_wallets_in_deterministic_uuid_order_to_prevent_deadlocks() {
        UUID higherUuidSource = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID lowerUuidTarget = UUID.fromString("00000000-0000-0000-0000-000000000001");

        Wallet sourceWallet = new Wallet(
                higherUuidSource, "user-source", Money.of(10_000L, Currency.JPY), 0L, FIXED_NOW, FIXED_NOW
        );
        Wallet targetWallet = new Wallet(
                lowerUuidTarget, "user-target", Money.of(2_000L, Currency.JPY), 0L, FIXED_NOW, FIXED_NOW
        );

        when(walletRepository.findByIdForUpdate(lowerUuidTarget)).thenReturn(Optional.of(targetWallet));
        when(walletRepository.findByIdForUpdate(higherUuidSource)).thenReturn(Optional.of(sourceWallet));

        TransferCommand command = new TransferCommand(
                higherUuidSource,
                lowerUuidTarget,
                Money.of(3_000L, Currency.JPY),
                "idem-key-001"
        );

        TransferResult result = transferFundsService.transfer(command);

        InOrder lockOrder = inOrder(walletRepository);
        lockOrder.verify(walletRepository).findByIdForUpdate(lowerUuidTarget);
        lockOrder.verify(walletRepository).findByIdForUpdate(higherUuidSource);

        assertThat(result).isInstanceOf(TransferResult.Completed.class);
        TransferResult.Completed completed = (TransferResult.Completed) result;
        assertThat(completed.sourceNewBalance()).isEqualTo(Money.of(7_000L, Currency.JPY));
        assertThat(targetWallet.balance()).isEqualTo(Money.of(5_000L, Currency.JPY));

        ArgumentCaptor<FundsTransferredEvent> eventCaptor = ArgumentCaptor.forClass(FundsTransferredEvent.class);
        verify(outboxEventPort).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().idempotencyKey()).isEqualTo("idem-key-001");
        assertThat(eventCaptor.getValue().amount()).isEqualTo(Money.of(3_000L, Currency.JPY));
    }

    @Test
    void should_return_rejected_result_and_not_publish_outbox_event_when_funds_are_insufficient() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Wallet sourceWallet = new Wallet(
                sourceId, "user-source", Money.of(1_000L, Currency.JPY), 0L, FIXED_NOW, FIXED_NOW
        );
        Wallet targetWallet = new Wallet(
                targetId, "user-target", Money.of(5_000L, Currency.JPY), 0L, FIXED_NOW, FIXED_NOW
        );

        when(walletRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(targetWallet));

        TransferCommand command = new TransferCommand(
                sourceId,
                targetId,
                Money.of(2_500L, Currency.JPY),
                "idem-key-002"
        );

        TransferResult result = transferFundsService.transfer(command);

        assertThat(result).isEqualTo(new TransferResult.RejectedInsufficientFunds(
                sourceId,
                Money.of(1_000L, Currency.JPY),
                Money.of(2_500L, Currency.JPY),
                false
        ));
        verify(walletRepository, never()).save(any());
        verify(outboxEventPort, never()).publish(any());

        ArgumentCaptor<Transfer> transferCaptor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository).insert(transferCaptor.capture());
        assertThat(transferCaptor.getValue().status()).isEqualTo(TransferStatus.REJECTED_INSUFFICIENT_FUNDS);
        assertThat(transferCaptor.getValue().idempotencyKey()).isEqualTo("idem-key-002");
    }

    @Test
    void should_replay_stored_transfer_without_moving_money_when_idempotency_key_was_already_used() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Balances as they are now, after the original transfer already ran.
        Wallet sourceWallet = new Wallet(
                sourceId, "user-source", Money.of(7_000L, Currency.JPY), 1L, FIXED_NOW, FIXED_NOW
        );
        Wallet targetWallet = new Wallet(
                targetId, "user-target", Money.of(3_000L, Currency.JPY), 1L, FIXED_NOW, FIXED_NOW
        );
        when(walletRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(targetWallet));

        Instant originalTime = FIXED_NOW.minusSeconds(30);
        Transfer original = new Transfer(
                UUID.randomUUID(), "idem-key-003", sourceId, targetId,
                Money.of(3_000L, Currency.JPY), TransferStatus.COMPLETED,
                Money.of(7_000L, Currency.JPY), originalTime
        );
        when(transferRepository.findByIdempotencyKey("idem-key-003")).thenReturn(Optional.of(original));

        TransferResult result = transferFundsService.transfer(new TransferCommand(
                sourceId, targetId, Money.of(3_000L, Currency.JPY), "idem-key-003"
        ));

        assertThat(result).isEqualTo(new TransferResult.Completed(
                original.id(), sourceId, targetId,
                Money.of(3_000L, Currency.JPY), Money.of(7_000L, Currency.JPY), originalTime,
                true
        ));
        assertThat(sourceWallet.balance()).isEqualTo(Money.of(7_000L, Currency.JPY));
        verify(walletRepository, never()).save(any());
        verify(transferRepository, never()).insert(any());
        verify(outboxEventPort, never()).publish(any());

        // The key must be checked only once the wallet locks are held (see TransferFundsService).
        InOrder order = inOrder(walletRepository, transferRepository);
        order.verify(walletRepository).findByIdForUpdate(sourceId);
        order.verify(walletRepository).findByIdForUpdate(targetId);
        order.verify(transferRepository).findByIdempotencyKey("idem-key-003");
    }

    @Test
    void should_reject_reused_idempotency_key_when_the_request_differs_from_the_stored_one() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Wallet sourceWallet = new Wallet(
                sourceId, "user-source", Money.of(7_000L, Currency.JPY), 1L, FIXED_NOW, FIXED_NOW
        );
        Wallet targetWallet = new Wallet(
                targetId, "user-target", Money.of(3_000L, Currency.JPY), 1L, FIXED_NOW, FIXED_NOW
        );
        when(walletRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(targetWallet));

        Transfer original = new Transfer(
                UUID.randomUUID(), "idem-key-004", sourceId, targetId,
                Money.of(3_000L, Currency.JPY), TransferStatus.COMPLETED,
                Money.of(7_000L, Currency.JPY), FIXED_NOW
        );
        when(transferRepository.findByIdempotencyKey("idem-key-004")).thenReturn(Optional.of(original));

        // Same key, different amount: a client bug that must not be answered with the old result.
        TransferCommand differentAmount = new TransferCommand(
                sourceId, targetId, Money.of(5_000L, Currency.JPY), "idem-key-004"
        );

        assertThatThrownBy(() -> transferFundsService.transfer(differentAmount))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
        assertThat(sourceWallet.balance()).isEqualTo(Money.of(7_000L, Currency.JPY));
        verify(walletRepository, never()).save(any());
        verify(transferRepository, never()).insert(any());
        verify(outboxEventPort, never()).publish(any());
    }
}
