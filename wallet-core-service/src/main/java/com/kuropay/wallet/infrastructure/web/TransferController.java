package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferCommand;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferResult;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyService;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyService.IdempotentExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferFundsUseCase transferFundsUseCase;
    private final RedisIdempotencyService idempotencyService;

    public TransferController(
            TransferFundsUseCase transferFundsUseCase,
            RedisIdempotencyService idempotencyService
    ) {
        this.transferFundsUseCase = transferFundsUseCase;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping(version = "1.0")
    public ResponseEntity<?> createTransfer(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) throws Exception {
        return ScopedValue.where(RequestContext.IDEMPOTENCY_KEY, idempotencyKey).call(() -> {
            TransferCommand command = new TransferCommand(
                    request.sourceWalletId(),
                    request.targetWalletId(),
                    new Money(request.amount(), request.currency()),
                    RequestContext.IDEMPOTENCY_KEY.get()
            );

            IdempotentExecution<CachedTransferOutcome> execution = idempotencyService.executeIdempotently(
                    idempotencyKey,
                    CachedTransferOutcome.class,
                    () -> {
                        TransferResult result = transferFundsUseCase.transfer(command);
                        return switch (result) {
                            case TransferResult.Completed completed -> new CachedTransferOutcome(
                                    true,
                                    new TransferResponse(
                                            completed.transferId(),
                                            completed.sourceWalletId(),
                                            completed.targetWalletId(),
                                            completed.transferredAmount().amount(),
                                            completed.transferredAmount().currency(),
                                            completed.sourceNewBalance().amount(),
                                            completed.completedAt()
                                    ),
                                    null
                            );
                            case TransferResult.RejectedInsufficientFunds rejected -> new CachedTransferOutcome(
                                    false,
                                    null,
                                    new InsufficientFundsPayload(
                                            rejected.sourceWalletId(),
                                            rejected.availableBalance().amount(),
                                            rejected.requestedAmount().amount(),
                                            rejected.availableBalance().currency()
                                    )
                            );
                        };
                    }
            );

            String replayedHeader = Boolean.toString(execution.replayed());
            CachedTransferOutcome outcome = execution.response();

            if (outcome.completed() && outcome.success() != null) {
                return ResponseEntity.ok()
                        .header("X-Idempotent-Replayed", replayedHeader)
                        .body(outcome.success());
            }

            InsufficientFundsPayload rejected = outcome.rejected();
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "Insufficient funds in source wallet " + (rejected != null ? rejected.walletId() : "")
            );
            problem.setTitle("Insufficient Funds");
            problem.setType(URI.create("https://api.kuropay.com/errors/insufficient-funds"));
            if (rejected != null) {
                problem.setProperty("walletId", rejected.walletId());
                problem.setProperty("availableBalance", rejected.availableBalance());
                problem.setProperty("requestedAmount", rejected.requestedAmount());
                problem.setProperty("currency", rejected.currency());
            }
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .header("X-Idempotent-Replayed", replayedHeader)
                    .body(problem);
        });
    }

    public record TransferRequest(
            @NotNull UUID sourceWalletId,
            @NotNull UUID targetWalletId,
            @NotNull BigDecimal amount,
            @NotNull Currency currency
    ) {
    }

    public record TransferResponse(
            UUID transferId,
            UUID sourceWalletId,
            UUID targetWalletId,
            BigDecimal transferredAmount,
            Currency currency,
            BigDecimal sourceNewBalance,
            Instant completedAt
    ) {
    }

    public record InsufficientFundsPayload(
            UUID walletId,
            BigDecimal availableBalance,
            BigDecimal requestedAmount,
            Currency currency
    ) {
    }

    public record CachedTransferOutcome(
            boolean completed,
            @Nullable TransferResponse success,
            @Nullable InsufficientFundsPayload rejected
    ) {
    }
}
