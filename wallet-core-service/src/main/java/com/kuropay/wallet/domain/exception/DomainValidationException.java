package com.kuropay.wallet.domain.exception;

/**
 * Invalid input rejected by a domain or application rule (e.g. too many decimals for a
 * currency). Its message is written for the API client and is safe to return as-is.
 *
 * <p>Any other {@link IllegalArgumentException} signals a bug or corrupt data, not bad client
 * input, and must surface as a 500 without its message.
 */
public class DomainValidationException extends IllegalArgumentException {

    public DomainValidationException(String message) {
        super(message);
    }

    public DomainValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
