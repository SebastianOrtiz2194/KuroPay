package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.event.FundsDepositedEvent;
import com.kuropay.wallet.domain.event.FundsTransferredEvent;
import com.kuropay.wallet.domain.event.WalletEvent;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxPersistenceAdapter implements OutboxEventPort {

    private final SpringDataOutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    public OutboxPersistenceAdapter(
            SpringDataOutboxRepository outboxRepository,
            JsonMapper jsonMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void publish(WalletEvent event) {
        // No default branch: WalletEvent is sealed, so a new event type fails compilation here
        // until someone decides which aggregate it belongs to.
        OutboxEventJpaEntity entity = switch (event) {
            case FundsTransferredEvent transferred ->
                    toEntity(transferred, "WALLET_TRANSFER", transferred.transferId());
            case FundsDepositedEvent deposited ->
                    toEntity(deposited, "WALLET_DEPOSIT", deposited.depositId());
        };
        outboxRepository.save(entity);
    }

    private OutboxEventJpaEntity toEntity(WalletEvent event, String aggregateType, UUID aggregateId) {
        return new OutboxEventJpaEntity(
                event.eventId(),
                aggregateType,
                aggregateId.toString(),
                event.getClass().getSimpleName(),
                jsonMapper.writeValueAsString(event),
                "PENDING",
                event.occurredAt()
        );
    }
}
