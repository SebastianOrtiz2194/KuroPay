package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WalletNotFoundException.class)
    public ProblemDetail handleWalletNotFound(WalletNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Wallet Not Found");
        problem.setType(URI.create("https://api.kuropay.com/errors/wallet-not-found"));
        problem.setProperty("walletId", ex.walletId());
        return problem;
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ProblemDetail handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException ex) {
        // 422 per the IETF Idempotency-Key draft: the key was reused with a different payload.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Idempotency Key Reused");
        problem.setType(URI.create("https://api.kuropay.com/errors/idempotency-key-reused"));
        problem.setProperty("idempotencyKey", ex.idempotencyKey());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create("https://api.kuropay.com/errors/invalid-request"));
        return problem;
    }
}
