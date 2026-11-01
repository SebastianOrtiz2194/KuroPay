package com.kuropay.wallet.domain.port.out;

import com.kuropay.wallet.domain.event.FundsTransferredEvent;

public interface OutboxEventPort {

    void publish(FundsTransferredEvent event);
}
