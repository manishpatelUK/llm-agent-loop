package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.time.Duration;
import java.util.Objects;

/**
 * A tool as registered on a {@link ToolRegistry}: its definition (told to the model), its local
 * implementation, how long a call may take, and whether its results carry outside content.
 *
 * @param timeout         how long one call may run before it's interrupted and the model is told it
 *                        timed out; {@code null} means the loop's default ({@code AgentLoop.builder().toolTimeout(...)})
 * @param untrustedOutput whether results contain content from outside sources (web pages, emails,
 *                        files, API or MCP responses) that could carry instructions aimed at the model.
 *                        Such results are labelled as untrusted for the model, screened if a
 *                        {@code ContentScreener} is set, and mark the run as having read untrusted
 *                        content (see {@code ToolContext.untrustedSources()}). Built-in tools that read
 *                        outside content set it; set it on your own with {@link #withUntrustedOutput()}.
 */
public record RegisteredTool(ToolDefinition definition, ToolHandler handler, Duration timeout, boolean untrustedOutput) {

    public RegisteredTool {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive when given");
        }
    }

    /** With the loop's default timeout. */
    public RegisteredTool(ToolDefinition definition, ToolHandler handler) {
        this(definition, handler, null, false);
    }

    public RegisteredTool(ToolDefinition definition, ToolHandler handler, Duration timeout) {
        this(definition, handler, timeout, false);
    }

    /** This tool with its own timeout — longer for tools that legitimately wait (a human, another agent), shorter for quick ones. */
    public RegisteredTool withTimeout(Duration timeout) {
        return new RegisteredTool(definition, handler, Objects.requireNonNull(timeout, "timeout"), untrustedOutput);
    }

    /** This tool, marked as returning outside content — see {@link #untrustedOutput()}. */
    public RegisteredTool withUntrustedOutput() {
        return new RegisteredTool(definition, handler, timeout, true);
    }
}
