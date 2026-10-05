package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.time.Duration;
import java.util.Objects;

/**
 * A tool as registered on a {@link ToolRegistry}: its definition (told to the model), its local
 * implementation, and how long a call may take.
 *
 * @param timeout how long one call may run before it's interrupted and the model is told it timed
 *                out; {@code null} means the loop's default ({@code AgentLoop.builder().toolTimeout(...)})
 */
public record RegisteredTool(ToolDefinition definition, ToolHandler handler, Duration timeout) {

    public RegisteredTool {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive when given");
        }
    }

    /** With the loop's default timeout. */
    public RegisteredTool(ToolDefinition definition, ToolHandler handler) {
        this(definition, handler, null);
    }

    /** This tool with its own timeout — longer for tools that legitimately wait (a human, another agent), shorter for quick ones. */
    public RegisteredTool withTimeout(Duration timeout) {
        return new RegisteredTool(definition, handler, Objects.requireNonNull(timeout, "timeout"));
    }
}
