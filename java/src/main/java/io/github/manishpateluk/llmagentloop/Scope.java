package io.github.manishpateluk.llmagentloop;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Whose data a run works with: the same agent definition serves many end users, and this is what
 * keeps each user's memory and workspace separate. The identifiers are opaque to this library —
 * the implementor supplies whatever its own tenant/user/session ids are, per run, via
 * {@link LoopRequest#scope()}.
 *
 * <p>Stores never see the full scope directly: each is handed {@link #atLevel} its configured
 * {@link ScopeLevel}, so e.g. at {@link ScopeLevel#USER} the session id is {@code null} and a
 * user's sessions all share one partition. Tools reach stores only through views already bound to
 * the run's scope, so a model can never name — and so never reach — another user's data.
 *
 * @param tenantId  the implementor's customer/organization; always present
 * @param userId    the end user within the tenant; {@code null} only in a {@link ScopeLevel#TENANT}-level key
 * @param sessionId the conversation/thread; {@code null} in {@link ScopeLevel#TENANT}- and {@link ScopeLevel#USER}-level keys
 */
public record Scope(String tenantId, String userId, String sessionId) {

    /** Tenant id used by {@link #forUser}, for single-tenant implementors. */
    public static final String DEFAULT_TENANT = "default";

    public Scope {
        requireNonBlank(tenantId, "tenantId");
        if (userId != null) {
            requireNonBlank(userId, "userId");
        }
        if (sessionId != null) {
            requireNonBlank(sessionId, "sessionId");
            if (userId == null) {
                throw new IllegalArgumentException("sessionId requires a userId");
            }
        }
    }

    /** A full scope; every id is required. */
    public static Scope of(String tenantId, String userId, String sessionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        return new Scope(tenantId, userId, sessionId);
    }

    /** A full scope under {@link #DEFAULT_TENANT}, for implementors without tenants. */
    public static Scope forUser(String userId, String sessionId) {
        return of(DEFAULT_TENANT, userId, sessionId);
    }

    /**
     * Used when a run supplies no scope: a user and session no other run will ever share, so
     * nothing leaks between callers who forgot to pass one — at the cost of nothing persisting
     * beyond that run either.
     */
    static Scope ephemeral(UUID executionId) {
        String id = "run-" + executionId;
        return new Scope("ephemeral", id, id);
    }

    /** This scope with every id below {@code level} cleared — the partition key for data stored at that level. */
    public Scope atLevel(ScopeLevel level) {
        return switch (Objects.requireNonNull(level, "level")) {
            case TENANT -> new Scope(tenantId, null, null);
            case USER -> new Scope(tenantId, userId, null);
            case SESSION -> this;
        };
    }

    /**
     * A stable string form of this scope for use as a storage key — a database column, an S3
     * prefix, a cache key. Each id is URL-encoded (so ids containing {@code /} can't collide or
     * escape a prefix) and a cleared level is {@code *}: e.g. {@code acme/alice/*} for a
     * {@link ScopeLevel#USER}-level key. Two scopes have the same key exactly when they're equal.
     */
    public String key() {
        return encode(tenantId) + "/" + encode(userId) + "/" + encode(sessionId);
    }

    private static String encode(String id) {
        // URLEncoder leaves '*' as-is, which would let a literal "*" id collide with a cleared level.
        return id == null ? "*" : URLEncoder.encode(id, StandardCharsets.UTF_8).replace("*", "%2A");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
