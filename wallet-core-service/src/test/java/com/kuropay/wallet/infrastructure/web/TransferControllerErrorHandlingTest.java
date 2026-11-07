package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase;
import com.kuropay.wallet.domain.exception.CurrencyMismatchException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache;
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

/**
 * Web-layer slice: no database, no Redis. Verifies that every client error comes back as an
 * RFC 9457 problem (application/problem+json) and that server errors never leak internals.
 */
@WebMvcTest(TransferController.class)
class TransferControllerErrorHandlingTest {

    private static final String SOURCE = "00000000-0000-0000-0000-000000000001";
    private static final String TARGET = "00000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private TransferFundsUseCase transferFundsUseCase;

    @MockitoBean
    private RedisIdempotencyCache idempotencyCache;

    @Test
    void malformed_json_should_return_400_problem() {
        MvcTestResult result = post("idem-1", "{ this is not json");

        assertProblem(result, HttpStatus.BAD_REQUEST);
    }

    @Test
    void missing_amount_should_return_400_problem_listing_the_invalid_field() {
        MvcTestResult result = post("idem-1", """
                {"sourceWalletId": "%s", "targetWalletId": "%s", "currency": "JPY"}
                """.formatted(SOURCE, TARGET));

        assertInvalidRequest(result);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amount");
    }

    @Test
    void negative_amount_should_return_400_problem_listing_the_invalid_field() {
        MvcTestResult result = post("idem-1", body("-100", "JPY"));

        assertInvalidRequest(result);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amount");
    }

    @Test
    void missing_idempotency_key_header_should_return_400_problem() {
        MvcTestResult result = mvc.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("1000", "JPY"))
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST);
    }

    @Test
    void oversized_idempotency_key_should_return_400_problem_listing_the_header() {
        MvcTestResult result = post("k".repeat(256), body("1000", "JPY"));

        assertInvalidRequest(result);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("Idempotency-Key");
    }

    @Test
    void unknown_currency_should_return_400_problem() {
        MvcTestResult result = post("idem-1", body("1000", "XYZ"));

        assertProblem(result, HttpStatus.BAD_REQUEST);
    }

    @Test
    void unsupported_api_version_should_return_400_problem() {
        MvcTestResult result = mvc.post().uri("/api/transfers")
                .header("X-API-Version", "9.0")
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("1000", "JPY"))
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST);
    }

    @Test
    void jpy_amount_with_decimals_should_return_400_invalid_request_from_the_domain_rule() {
        MvcTestResult result = post("idem-1", body("100.5", "JPY"));

        assertInvalidRequest(result);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("decimal places");
    }

    @Test
    void same_source_and_target_wallet_should_return_400_invalid_request() {
        MvcTestResult result = post("idem-1", """
                {"sourceWalletId": "%s", "targetWalletId": "%s", "amount": 1000, "currency": "JPY"}
                """.formatted(SOURCE, SOURCE));

        assertInvalidRequest(result);
    }

    @Test
    void currency_different_from_the_wallet_currency_should_return_422_problem() {
        when(transferFundsUseCase.transfer(any()))
                .thenThrow(new CurrencyMismatchException(Currency.JPY, Currency.USD));

        MvcTestResult result = post("idem-1", body("10.00", "USD"));

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Currency Mismatch");
    }

    @Test
    void unexpected_illegal_argument_from_a_bug_should_be_500_not_a_client_error() {
        when(transferFundsUseCase.transfer(any()))
                .thenThrow(new IllegalArgumentException("ownerId must not be blank"));

        MvcTestResult result = post("idem-1", body("1000", "JPY"));

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().doesNotContain("ownerId");
    }

    @Test
    void unexpected_exception_should_be_500_without_leaking_its_message() {
        when(transferFundsUseCase.transfer(any()))
                .thenThrow(new IllegalStateException("connection to db-prod-01:5432 refused"));

        MvcTestResult result = post("idem-1", body("1000", "JPY"));

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().doesNotContain("db-prod-01");
    }

    private MvcTestResult post(String idempotencyKey, String json) {
        return mvc.post().uri("/api/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static String body(String amount, String currency) {
        return """
                {"sourceWalletId": "%s", "targetWalletId": "%s", "amount": %s, "currency": "%s"}
                """.formatted(SOURCE, TARGET, amount, currency);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status) {
        assertThat(result).hasStatus(status);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    }

    private static void assertInvalidRequest(MvcTestResult result) {
        assertProblem(result, HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Invalid Request");
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo("https://api.kuropay.com/errors/invalid-request");
    }
}
