package com.kuropay.wallet.application.service;

import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletCommand;
import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletResult;
import com.kuropay.wallet.domain.exception.DuplicateWalletException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import com.kuropay.wallet.domain.port.out.WalletRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-09T09:00:00Z");

    @Mock
    private WalletRepositoryPort walletRepository;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void should_create_an_empty_wallet_for_the_owner() {
        when(walletRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateWalletResult result = walletService.createWallet(new CreateWalletCommand("alice", Currency.JPY));

        assertThat(result).isInstanceOf(CreateWalletResult.Created.class);
        Wallet wallet = ((CreateWalletResult.Created) result).wallet();
        assertThat(wallet.ownerId()).isEqualTo("alice");
        assertThat(wallet.balance()).isEqualTo(Money.zero(Currency.JPY));
        assertThat(wallet.createdAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void should_return_the_existing_wallet_when_the_owner_already_has_one_in_that_currency() {
        Wallet existing = new Wallet(
                UUID.randomUUID(), "alice", Money.of(500L, Currency.JPY), 3L, FIXED_NOW, FIXED_NOW
        );
        when(walletRepository.save(any())).thenThrow(new DuplicateWalletException("alice", Currency.JPY));
        when(walletRepository.findByOwnerIdAndCurrency("alice", Currency.JPY)).thenReturn(Optional.of(existing));

        CreateWalletResult result = walletService.createWallet(new CreateWalletCommand("alice", Currency.JPY));

        assertThat(result).isEqualTo(new CreateWalletResult.AlreadyExists(existing));
    }

    @Test
    void should_throw_wallet_not_found_for_an_unknown_id() {
        UUID unknown = UUID.randomUUID();
        when(walletRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getWallet(unknown))
                .isInstanceOf(WalletNotFoundException.class);
    }
}
