package io.github.manishpateluk.llmagentloop;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Makes the id in a run's untrusted-content markers ({@code [[untrusted-content <id> ...]]}): an HMAC of the
 * run's scope under a secret key. Content from outside can't forge an end marker without the id, and the id
 * can't be worked out without the key. Every run in one conversation (one {@link Scope}) gets the same id,
 * so the system instructions that name it are byte-identical from turn to turn and stay in the provider's
 * prompt cache; a run without a scope has a scope of its own, so its id is its own too.
 *
 * <p>The key is random per {@code AgentLoop} unless {@code AgentLoop.Builder.untrustedMarkerSecret} sets one,
 * which keeps ids (and the cache) stable across restarts and across several instances serving the same
 * conversations.
 */
final class UntrustedMarkers {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    private UntrustedMarkers(byte[] key) {
        this.key = key;
    }

    /** A random key, for one {@code AgentLoop}. */
    static UntrustedMarkers random() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new UntrustedMarkers(key);
    }

    /** A caller's secret, for ids that are the same in every process that has it. */
    static UntrustedMarkers withSecret(String secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length() < 16) {
            throw new IllegalArgumentException("untrustedMarkerSecret must be at least 16 characters");
        }
        return new UntrustedMarkers(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** The marker id for runs in {@code scope}: 8 hex characters. */
    String markerId(Scope scope) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(scope.key().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
