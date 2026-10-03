package io.github.manishpateluk.llmagentloop.memory;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Objects;

/**
 * A {@link MemoryStore} bound to one {@link Scope} — what a tool receives via
 * {@code ToolContext.memory()}. Having no way to name a different scope is the point: whatever
 * the model asks for, it only ever reaches the current user's memory.
 */
public final class ScopedMemory {

    private final MemoryStore store;
    private final Scope scope;

    ScopedMemory(MemoryStore store, Scope scope) {
        this.store = Objects.requireNonNull(store, "store");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public MemoryEntry save(String content, List<String> tags) {
        return store.save(scope, content, tags);
    }

    public List<MemoryEntry> search(String query, int limit) {
        return store.search(scope, query, limit);
    }

    public boolean delete(String id) {
        return store.delete(scope, id);
    }

    /** The partition key this view is bound to (already reduced to its level). */
    public Scope scope() {
        return scope;
    }
}
