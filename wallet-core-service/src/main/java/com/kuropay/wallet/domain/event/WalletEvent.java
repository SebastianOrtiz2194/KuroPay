package com.kuropay.wallet.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Every event the wallet domain can emit through the outbox. Sealed on purpose: adding a new
 * event forces every exhaustive {@code switch} over it (e.g. the outbox adapter) to handle it,
 * or the build fails.
 */
public sealed interface WalletEvent permits FundsTransferredEvent, FundsDepositedEvent {

    UUID eventId();

    Instant occurredAt();
}
