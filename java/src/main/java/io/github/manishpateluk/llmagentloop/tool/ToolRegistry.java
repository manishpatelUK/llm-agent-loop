package io.github.manishpateluk.llmagentloop.tool;

import com.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tools an {@link io.github.manishpateluk.llmagentloop.AgentLoop} may call while executing a
 * step. Any tool the model requests that isn't registered here is handed to the run's
 * {@link UnregisteredToolHandler} to resolve; if it can't, the run ends with an
 * {@code UnregisteredToolException}.
 */
public final class ToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry register(ToolDefinition definition, ToolHandler handler) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        tools.put(definition.getName(), new RegisteredTool(definition, handler));
        return this;
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }
}
