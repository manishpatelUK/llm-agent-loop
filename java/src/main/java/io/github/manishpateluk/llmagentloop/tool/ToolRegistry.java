package io.github.manishpateluk.llmagentloop.tool;

import com.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tools an {@link io.github.manishpateluk.llmagentloop.AgentLoop} may call while executing a
 * step. Any tool the model requests that isn't registered here is handed to the run's
 * {@link UnregisteredToolHandler} to resolve; if it can't, the run ends with an
 * {@code UnregisteredToolException}.
 *
 * <p>Built-in tools come as ready-made lists to {@link #registerAll}, e.g.
 * {@code registry.registerAll(MemoryTools.all())}. Registering a name again replaces the earlier tool.
 */
public final class ToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry register(ToolDefinition definition, ToolHandler handler) {
        return register(new RegisteredTool(definition, handler));
    }

    /** For tools that only need their arguments, not the {@link ToolContext} of the run calling them. */
    public ToolRegistry register(ToolDefinition definition, Function<Map<String, Object>, String> handler) {
        Objects.requireNonNull(handler, "handler");
        return register(definition, (arguments, context) -> handler.apply(arguments));
    }

    public ToolRegistry register(RegisteredTool tool) {
        Objects.requireNonNull(tool, "tool");
        tools.put(tool.definition().getName(), tool);
        return this;
    }

    public ToolRegistry registerAll(Collection<RegisteredTool> tools) {
        tools.forEach(this::register);
        return this;
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }
}
