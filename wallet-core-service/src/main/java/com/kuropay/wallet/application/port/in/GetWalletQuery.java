package com.kuropay.wallet.application.port.in;

import com.kuropay.wallet.domain.exception.WalletNotFoundException;
import com.kuropay.wallet.domain.model.Wallet;
import java.util.UUID;

public interface GetWalletQuery {

    /**
     * @throws WalletNotFoundException if no wallet has this id
     */
    Wallet getWallet(UUID walletId);
}
