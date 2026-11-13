package com.kuropay.wallet.domain.port.out;

import com.kuropay.wallet.domain.event.WalletEvent;

public interface OutboxEventPort {

    void publish(WalletEvent event);
}
