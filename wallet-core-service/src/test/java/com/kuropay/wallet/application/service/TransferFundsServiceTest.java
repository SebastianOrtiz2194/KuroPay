package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferCommand;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferResult;
import com.kuropay.wallet.domain.event.FundsTransferredEvent;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
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
    private OutboxEventPort outboxEventPort;

    private TransferFundsService transferFundsService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        transferFundsService = new TransferFundsService(walletRepository, outboxEventPort, fixedClock);
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
                Money.of(2_500L, Currency.JPY)
        ));
        verify(walletRepository, never()).save(any());
        verify(outboxEventPort, never()).publish(any());
    }
}
