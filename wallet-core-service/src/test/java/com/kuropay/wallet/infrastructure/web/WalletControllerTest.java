package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.CreateWalletUseCase;
import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletResult;
import com.kuropay.wallet.application.port.in.GetWalletQuery;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.model.Wallet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(WalletController.class)
class WalletControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");
    private static final UUID WALLET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateWalletUseCase createWalletUseCase;

    @MockitoBean
    private GetWalletQuery getWalletQuery;

    @Test
    void creating_a_wallet_should_return_201_with_location_and_body() {
        when(createWalletUseCase.createWallet(any()))
                .thenReturn(new CreateWalletResult.Created(wallet(Money.zero(Currency.JPY))));

        MvcTestResult result = postWallet("""
                {"ownerId": "alice", "currency": "JPY"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).headers().hasValue("Location", "http://localhost/api/wallets/" + WALLET_ID);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(WALLET_ID.toString());
        assertThat(result).bodyJson().extractingPath("$.balance").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("JPY");
    }

    @Test
    void creating_a_wallet_that_already_exists_should_return_409_pointing_at_the_existing_one() {
        when(createWalletUseCase.createWallet(any()))
                .thenReturn(new CreateWalletResult.AlreadyExists(wallet(Money.of(500L, Currency.JPY))));

        MvcTestResult result = postWallet("""
                {"ownerId": "alice", "currency": "JPY"}
                """);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).headers().hasValue("Location", "http://localhost/api/wallets/" + WALLET_ID);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Wallet Already Exists");
        assertThat(result).bodyJson().extractingPath("$.existingWalletId").isEqualTo(WALLET_ID.toString());
    }

    @Test
    void blank_owner_should_return_400_problem_listing_the_field() {
        MvcTestResult result = postWallet("""
                {"ownerId": " ", "currency": "JPY"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("ownerId");
    }

    @Test
    void getting_a_wallet_should_return_its_balance() {
        when(getWalletQuery.getWallet(WALLET_ID)).thenReturn(wallet(Money.of(7_500L, Currency.JPY)));

        MvcTestResult result = mvc.get().uri("/api/wallets/{id}", WALLET_ID).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.balance").isEqualTo(7500);
        assertThat(result).bodyJson().extractingPath("$.ownerId").isEqualTo("alice");
    }

    @Test
    void getting_an_unknown_wallet_should_return_404_problem() {
        when(getWalletQuery.getWallet(WALLET_ID)).thenThrow(new WalletNotFoundException(WALLET_ID));

        MvcTestResult result = mvc.get().uri("/api/wallets/{id}", WALLET_ID).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Wallet Not Found");
    }

    @Test
    void malformed_wallet_id_should_return_400_problem() {
        MvcTestResult result = mvc.get().uri("/api/wallets/not-a-uuid").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private MvcTestResult postWallet(String json) {
        return mvc.post().uri("/api/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static Wallet wallet(Money balance) {
        return new Wallet(WALLET_ID, "alice", balance, 0L, NOW, NOW);
    }
}
