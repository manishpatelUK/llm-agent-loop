package io.github.manishpateluk.llmagentloop;

/**
 * How widely a store's data is shared across {@link Scope}s — e.g. {@code AgentLoop.builder().memoryLevel(...)}.
 * Data saved at a level is visible to every run whose {@link Scope} matches down to that level.
 */
public enum ScopeLevel {

    /** Shared by every user of the tenant. Use deliberately: anything one user's agent saves, every other user's agent can read. */
    TENANT,

    /** Shared across all of one user's sessions, and only theirs. The default for memory and workspace. */
    USER,

    /** Private to a single session; gone from view as soon as the implementor starts a new one. */
    SESSION
}
