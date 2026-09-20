package io.github.manishpateluk.llmagentloop.memory;

import java.util.List;

/**
 * Long-term memory an {@link io.github.manishpateluk.llmagentloop.AgentLoop} consults for extra
 * context at each step. Placeholder for now — {@link #NONE} is a no-op; a real implementation
 * (e.g. backed by a vector store) lands in a later pass.
 */
@FunctionalInterface
public interface MemoryStore {

    MemoryStore NONE = query -> List.of();

    List<String> recall(String query);
}
