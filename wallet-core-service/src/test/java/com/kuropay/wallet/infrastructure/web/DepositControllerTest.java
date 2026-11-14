package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.DepositFundsUseCase;
import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositResult;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.infrastructure.idempotency.IdempotentOperation;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache.CachedResponse;
import com.kuropay.wallet.infrastructure.idempotency.RequestFingerprint;
import com.kuropay.wallet.infrastructure.web.DepositController.DepositResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Web-layer slice: no database, no Redis. The use case and the cache are mocks. */
@WebMvcTest(DepositController.class)
class DepositControllerTest {

    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEPOSIT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Instant COMPLETED_AT = Instant.parse("2026-11-14T03:00:00Z");

    /** What the controller computes for "1000 JPY into WALLET_ID". */
    private static final String FINGERPRINT = RequestFingerprint.of(List.of(WALLET_ID.toString(), "1000", "JPY"));

    private static final DepositResponse STORED_RESPONSE = new DepositResponse(
            DEPOSIT_ID, WALLET_ID, new BigDecimal("1000"), Currency.JPY, new BigDecimal("1500"), COMPLETED_AT
    );

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private DepositFundsUseCase depositFundsUseCase;

    @MockitoBean
    private RedisIdempotencyCache idempotencyCache;

    @Test
    void fresh_deposit_should_return_200_and_be_cached_under_the_deposit_operation() {
        when(depositFundsUseCase.deposit(any())).thenReturn(new DepositResult(
                DEPOSIT_ID, WALLET_ID, Money.of(1_000L, Currency.JPY), Money.of(1_500L, Currency.JPY),
                COMPLETED_AT, false
        ));

        MvcTestResult result = post(WALLET_ID.toString(), "idem-1", body("1000", "JPY"));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).hasHeader("X-Idempotent-Replayed", "false");
        assertThat(result).bodyJson().extractingPath("$.depositId").isEqualTo(DEPOSIT_ID.toString());
        assertThat(result).bodyJson().extractingPath("$.newBalance").isEqualTo(1500);
        verify(idempotencyCache).store(IdempotentOperation.DEPOSIT, "idem-1", FINGERPRINT, STORED_RESPONSE);
    }

    @Test
    void retry_found_in_the_cache_should_be_replayed_without_calling_the_use_case() {
        when(idempotencyCache.find(IdempotentOperation.DEPOSIT, "idem-1", DepositResponse.class))
                .thenReturn(Optional.of(new CachedResponse<>(FINGERPRINT, STORED_RESPONSE)));

        MvcTestResult result = post(WALLET_ID.toString(), "idem-1", body("1000", "JPY"));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).hasHeader("X-Idempotent-Replayed", "true");
        assertThat(result).bodyJson().extractingPath("$.depositId").isEqualTo(DEPOSIT_ID.toString());
        verifyNoInteractions(depositFundsUseCase);
    }

    @Test
    void cached_key_reused_with_a_different_amount_should_return_422_without_calling_the_use_case() {
        when(idempotencyCache.find(IdempotentOperation.DEPOSIT, "idem-1", DepositResponse.class))
                .thenReturn(Optional.of(new CachedResponse<>(FINGERPRINT, STORED_RESPONSE)));

        MvcTestResult result = post(WALLET_ID.toString(), "idem-1", body("9999", "JPY"));

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Idempotency Key Reused");
        verifyNoInteractions(depositFundsUseCase);
    }

    @Test
    void unknown_wallet_should_return_404_problem() {
        when(depositFundsUseCase.deposit(any())).thenThrow(new WalletNotFoundException(WALLET_ID));

        MvcTestResult result = post(WALLET_ID.toString(), "idem-1", body("1000", "JPY"));

        assertProblem(result, HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.walletId").isEqualTo(WALLET_ID.toString());
    }

    @Test
    void negative_amount_should_return_400_problem_listing_the_invalid_field() {
        MvcTestResult result = post(WALLET_ID.toString(), "idem-1", body("-100", "JPY"));

        assertProblem(result, HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amount");
    }

    @Test
    void missing_idempotency_key_header_should_return_400_problem() {
        MvcTestResult result = mvc.post().uri("/api/wallets/{walletId}/deposits", WALLET_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("1000", "JPY"))
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST);
    }

    @Test
    void wallet_id_that_is_not_a_uuid_should_return_400_problem() {
        MvcTestResult result = post("not-a-uuid", "idem-1", body("1000", "JPY"));

        assertProblem(result, HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult post(String walletId, String idempotencyKey, String json) {
        return mvc.post().uri("/api/wallets/{walletId}/deposits", walletId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static String body(String amount, String currency) {
        return """
                {"amount": %s, "currency": "%s"}
                """.formatted(amount, currency);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status) {
        assertThat(result).hasStatus(status);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    }
}
