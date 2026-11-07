package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.domain.exception.CurrencyMismatchException;
import com.kuropay.wallet.domain.exception.DomainValidationException;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into RFC 9457 problems.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} makes every Spring MVC error (malformed
 * JSON, missing header, unsupported API version...) a problem too, instead of Spring Boot's own
 * error JSON. Rule of thumb: 4xx responses explain what the client did wrong; 5xx responses
 * reveal nothing about the server.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERROR_TYPE_BASE = "https://api.kuropay.com/errors/";

    @ExceptionHandler(WalletNotFoundException.class)
    public ProblemDetail handleWalletNotFound(WalletNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "wallet-not-found", "Wallet Not Found", ex.getMessage());
        problem.setProperty("walletId", ex.walletId());
        return problem;
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ProblemDetail handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException ex) {
        // 422 per the IETF Idempotency-Key draft: the key was reused with a different payload.
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency-key-reused", "Idempotency Key Reused", ex.getMessage());
        problem.setProperty("idempotencyKey", ex.idempotencyKey());
        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT,
                "currency-mismatch", "Currency Mismatch", ex.getMessage());
        problem.setProperty("walletCurrency", ex.expected());
        problem.setProperty("requestedCurrency", ex.actual());
        return problem;
    }

    @ExceptionHandler(DomainValidationException.class)
    public ProblemDetail handleDomainValidation(DomainValidationException ex) {
        return invalidRequest(ex.getMessage(), List.of());
    }

    /**
     * Last resort. Note there is deliberately no handler for plain IllegalArgumentException:
     * one that reaches here is a bug or corrupt data, so it is a 500, not the client's fault.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error", "Internal Server Error", "An unexpected error occurred");
    }

    /** Bean Validation on a @Valid body when method validation is not active. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        return handleExceptionInternal(ex, invalidRequest("Request validation failed", violations),
                headers, status, request);
    }

    /**
     * Method validation is active because the controller has constraints directly on a parameter
     * (the Idempotency-Key header). In that case Spring reports errors of the @Valid body here as
     * well, not as MethodArgumentNotValidException, so both are mapped to the same shape.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                bodyErrors.getFieldErrors().forEach(error -> violations.add(toViolation(error)));
            } else {
                String name = parameterName(result);
                result.getResolvableErrors().forEach(error ->
                        violations.add(new FieldViolation(name, error.getDefaultMessage())));
            }
        }
        return handleExceptionInternal(ex, invalidRequest("Request validation failed", violations),
                headers, status, request);
    }

    private static ProblemDetail invalidRequest(@Nullable String detail, List<FieldViolation> violations) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid Request", detail);
        if (!violations.isEmpty()) {
            problem.setProperty("errors", violations);
        }
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, @Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(ERROR_TYPE_BASE + type));
        return problem;
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    /** Report headers by their HTTP name ("Idempotency-Key"), not the Java parameter name. */
    private static String parameterName(ParameterValidationResult result) {
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isBlank()) {
            return header.value();
        }
        return Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "unknown");
    }

    public record FieldViolation(String field, @Nullable String message) {
    }
}
