package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.TransferFundsUseCase;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferCommand;
import com.kuropay.wallet.application.port.in.TransferFundsUseCase.TransferResult;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache.CachedResponse;
import com.kuropay.wallet.infrastructure.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private final RedisIdempotencyCache idempotencyCache;

    public TransferController(
            TransferFundsUseCase transferFundsUseCase,
            RedisIdempotencyCache idempotencyCache
    ) {
        this.transferFundsUseCase = transferFundsUseCase;
        this.idempotencyCache = idempotencyCache;
    }

    @PostMapping(version = "1.0")
    public ResponseEntity<?> createTransfer(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = TransferFundsUseCase.MAX_IDEMPOTENCY_KEY_LENGTH)
            String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferCommand command = new TransferCommand(
                request.sourceWalletId(),
                request.targetWalletId(),
                new Money(request.amount(), request.currency()),
                idempotencyKey
        );
        String fingerprint = fingerprintOf(command);

        // Fast path: a retry answered from Redis never touches the database or its row locks.
        Optional<CachedResponse<CachedTransferOutcome>> cached =
                idempotencyCache.find(idempotencyKey, CachedTransferOutcome.class);
        if (cached.isPresent()) {
            if (!cached.get().requestFingerprint().equals(fingerprint)) {
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
            return toResponseEntity(cached.get().response(), true);
        }

        // Slow path: the use case is the real guarantee (it replays from the transfers table).
        TransferResult result = transferFundsUseCase.transfer(command);
        CachedTransferOutcome outcome = toOutcome(result);
        idempotencyCache.store(idempotencyKey, fingerprint, outcome);
        return toResponseEntity(outcome, result.replayed());
    }

    /** Built from the command so amounts are already normalized to the currency scale. */
    private static String fingerprintOf(TransferCommand command) {
        return RequestFingerprint.of(List.of(
                command.sourceWalletId().toString(),
                command.targetWalletId().toString(),
                command.amount().amount().toPlainString(),
                command.amount().currency().name()
        ));
    }

    private static CachedTransferOutcome toOutcome(TransferResult result) {
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

    private static ResponseEntity<?> toResponseEntity(CachedTransferOutcome outcome, boolean replayed) {
        String replayedHeader = Boolean.toString(replayed);

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
    }

    public record TransferRequest(
            @NotNull UUID sourceWalletId,
            @NotNull UUID targetWalletId,
            @NotNull @Positive BigDecimal amount,
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
