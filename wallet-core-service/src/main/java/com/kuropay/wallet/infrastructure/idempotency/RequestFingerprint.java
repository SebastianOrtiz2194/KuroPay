package com.kuropay.wallet.infrastructure.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * SHA-256 of a canonical form of the request. Callers must pass already-normalized values
 * (e.g. amounts with the currency scale applied), so "2500" and "2500.00" hash the same.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(List<String> canonicalParts) {
        // A separator that cannot appear in UUIDs, amounts or currency codes avoids ambiguity
        // like ("ab", "c") vs ("a", "bc").
        String canonical = String.join("\u001F", canonicalParts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is guaranteed by the Java platform", ex);
        }
    }
}
