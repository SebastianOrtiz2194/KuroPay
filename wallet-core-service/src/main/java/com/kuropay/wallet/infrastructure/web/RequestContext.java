package com.kuropay.wallet.infrastructure.web;

public final class RequestContext {

    public static final ScopedValue<String> IDEMPOTENCY_KEY = ScopedValue.newInstance();

    private RequestContext() {
    }
}
