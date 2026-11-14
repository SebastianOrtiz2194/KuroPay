package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.DepositFundsUseCase;
import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositCommand;
import com.kuropay.wallet.application.port.in.DepositFundsUseCase.DepositResult;
import com.kuropay.wallet.domain.exception.DuplicateIdempotencyKeyException;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Money;
import com.kuropay.wallet.infrastructure.idempotency.IdempotentOperation;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache;
import com.kuropay.wallet.infrastructure.idempotency.RedisIdempotencyCache.CachedResponse;
import com.kuropay.wallet.infrastructure.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A deposit is a sub-resource of the wallet it credits, so the wallet id travels in the path.
 *
 * <p>Open to any caller only until authentication exists (Step 6). In a real system this is not
 * something an end user calls: it is triggered by a payment provider confirming that money
 * actually arrived.
 */
@RestController
@RequestMapping("/api/wallets/{walletId}/deposits")
public class DepositController {

    private final DepositFundsUseCase depositFundsUseCase;
    private final RedisIdempotencyCache idempotencyCache;

    public DepositController(
            DepositFundsUseCase depositFundsUseCase,
            RedisIdempotencyCache idempotencyCache
    ) {
        this.depositFundsUseCase = depositFundsUseCase;
        this.idempotencyCache = idempotencyCache;
    }

    @PostMapping(version = "1.0")
    public ResponseEntity<DepositResponse> deposit(
            @PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = DepositFundsUseCase.MAX_IDEMPOTENCY_KEY_LENGTH)
            String idempotencyKey,
            @Valid @RequestBody DepositRequest request
    ) {
        DepositCommand command = new DepositCommand(
                walletId,
                new Money(request.amount(), request.currency()),
                idempotencyKey
        );
        String fingerprint = fingerprintOf(command);

        // Fast path: a retry answered from Redis never takes the wallet row lock.
        Optional<CachedResponse<DepositResponse>> cached =
                idempotencyCache.find(IdempotentOperation.DEPOSIT, idempotencyKey, DepositResponse.class);
        if (cached.isPresent()) {
            if (!cached.get().requestFingerprint().equals(fingerprint)) {
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
            return toResponseEntity(cached.get().response(), true);
        }

        // Slow path: the use case is the real guarantee (it replays from the deposits table).
        DepositResult result = depositFundsUseCase.deposit(command);
        DepositResponse response = DepositResponse.from(result);
        idempotencyCache.store(IdempotentOperation.DEPOSIT, idempotencyKey, fingerprint, response);
        return toResponseEntity(response, result.replayed());
    }

    /**
     * The wallet id is part of the fingerprint even though it comes from the path: the same key
     * sent to another wallet is a different request and must be rejected, not replayed.
     */
    private static String fingerprintOf(DepositCommand command) {
        return RequestFingerprint.of(List.of(
                command.walletId().toString(),
                command.amount().amount().toPlainString(),
                command.amount().currency().name()
        ));
    }

    private static ResponseEntity<DepositResponse> toResponseEntity(DepositResponse response, boolean replayed) {
        return ResponseEntity.ok()
                .header("X-Idempotent-Replayed", Boolean.toString(replayed))
                .body(response);
    }

    /**
     * The currency is required although the wallet already has one: it is the client stating
     * what it believes it is depositing, so "1000 JPY" sent to a USD wallet is rejected instead
     * of silently becoming 1000 USD.
     */
    public record DepositRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull Currency currency
    ) {
    }

    public record DepositResponse(
            UUID depositId,
            UUID walletId,
            BigDecimal depositedAmount,
            Currency currency,
            BigDecimal newBalance,
            Instant completedAt
    ) {
        static DepositResponse from(DepositResult result) {
            return new DepositResponse(
                    result.depositId(),
                    result.walletId(),
                    result.depositedAmount().amount(),
                    result.depositedAmount().currency(),
                    result.newBalance().amount(),
                    result.completedAt()
            );
        }
    }
}
