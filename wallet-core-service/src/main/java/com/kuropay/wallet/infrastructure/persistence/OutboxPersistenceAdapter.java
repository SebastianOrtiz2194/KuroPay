package com.kuropay.wallet.infrastructure.persistence;

import com.kuropay.wallet.domain.event.FundsTransferredEvent;
import com.kuropay.wallet.domain.port.out.OutboxEventPort;
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
    public void publish(FundsTransferredEvent event) {
        String payloadJson = jsonMapper.writeValueAsString(event);
        OutboxEventJpaEntity entity = new OutboxEventJpaEntity(
                event.eventId(),
                "WALLET_TRANSFER",
                event.transferId().toString(),
                FundsTransferredEvent.class.getSimpleName(),
                payloadJson,
                "PENDING",
                event.occurredAt()
        );
        outboxRepository.save(entity);
    }
}
