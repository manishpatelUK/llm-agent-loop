package io.github.manishpateluk.llmagentloop.memory;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;

/**
 * Long-term memory, partitioned by {@link Scope}: implement this to back an agent's memory with
 * your own storage (a vector store, Postgres, ...). {@link InMemoryMemoryStore} is the ready-made
 * option for development and single-process use; {@link #NONE} (the default) stores nothing.
 *
 * <p>Every call receives the partition key already reduced to the configured
 * {@link io.github.manishpateluk.llmagentloop.ScopeLevel} (see {@link Scope#atLevel}) — an
 * implementation simply stores and looks up data under the {@code scope} it's given, and never
 * needs to reason about levels itself. Calls come from a run's own virtual thread, so blocking I/O
 * is fine; implementations must be safe for concurrent runs.
 *
 * <p>Used two ways: explicitly, by the model through the built-in {@code memory_*} tools; and
 * implicitly, by the loop searching it with each step's goal and adding the best matches to that
 * step's context.
 */
public interface MemoryStore {

    /** Remembers nothing: searches find nothing, and saving fails with a pointer to configure a real store. */
    MemoryStore NONE = new MemoryStore() {
        @Override
        public MemoryEntry save(Scope scope, String content, List<String> tags) {
            throw new IllegalStateException(
                    "No MemoryStore is configured: pass one to AgentLoop.builder().memory(...) to use the memory tools");
        }

        @Override
        public List<MemoryEntry> search(Scope scope, String query, int limit) {
            return List.of();
        }

        @Override
        public boolean delete(Scope scope, String id) {
            return false;
        }
    };

    /** Stores {@code content} under {@code scope}, assigning it an id. */
    MemoryEntry save(Scope scope, String content, List<String> tags);

    /**
     * Up to {@code limit} entries under {@code scope} most relevant to {@code query}, best first.
     * A blank {@code query} means "most recent first".
     */
    List<MemoryEntry> search(Scope scope, String query, int limit);

    /** Removes entry {@code id} from {@code scope}; {@code false} if there was no such entry there. */
    boolean delete(Scope scope, String id);

    /** A view of this store bound to {@code scope}, which is how runs and tools reach it. */
    default ScopedMemory scopedTo(Scope scope) {
        return new ScopedMemory(this, scope);
    }
}
