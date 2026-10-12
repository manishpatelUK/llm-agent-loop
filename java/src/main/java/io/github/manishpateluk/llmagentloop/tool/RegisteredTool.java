package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A tool as registered on a {@link ToolRegistry}: its definition (told to the model), its local
 * implementation, how long a call may take, whether its results carry outside content, and whether
 * it needs a workspace.
 *
 * @param timeout                    how long one call may run before it's interrupted and the model is told it
 *                                   timed out; {@code null} means the loop's default ({@code AgentLoop.builder().toolTimeout(...)})
 * @param untrustedOutput            whether results contain content from outside sources (web pages, emails,
 *                                   files, API or MCP responses) that could carry instructions aimed at the model.
 *                                   Such results are labelled as untrusted for the model, screened if a
 *                                   {@code ContentScreener} is set, and mark the run as having read untrusted
 *                                   content (see {@code ToolContext.untrustedSources()}). Built-in tools that read
 *                                   outside content set it; set it on your own with {@link #withUntrustedOutput()}.
 * @param requiresWorkspace          whether the tool is useless without a workspace: when the loop has none
 *                                   ({@code Workspace.NONE}), the model isn't offered it. See {@link #withRequiresWorkspace()}.
 * @param definitionWithoutWorkspace what the model is offered instead of {@code definition} when the loop has
 *                                   no workspace — the definition minus its workspace-only parameters; {@code null}
 *                                   if nothing about the tool depends on one. See {@link #withWorkspaceParameters}.
 */
public record RegisteredTool(
        ToolDefinition definition, ToolHandler handler, Duration timeout, boolean untrustedOutput,
        boolean requiresWorkspace, ToolDefinition definitionWithoutWorkspace) {

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

    public RegisteredTool(ToolDefinition definition, ToolHandler handler, Duration timeout, boolean untrustedOutput) {
        this(definition, handler, timeout, untrustedOutput, false, null);
    }

    /** This tool with its own timeout — longer for tools that legitimately wait (a human, another agent), shorter for quick ones. */
    public RegisteredTool withTimeout(Duration timeout) {
        return new RegisteredTool(definition, handler, Objects.requireNonNull(timeout, "timeout"), untrustedOutput,
                requiresWorkspace, definitionWithoutWorkspace);
    }

    /** This tool, marked as returning outside content — see {@link #untrustedOutput()}. */
    public RegisteredTool withUntrustedOutput() {
        return new RegisteredTool(definition, handler, timeout, true, requiresWorkspace, definitionWithoutWorkspace);
    }

    /** This tool, offered to the model only when the loop has a workspace — for tools that only work on workspace files. */
    public RegisteredTool withRequiresWorkspace() {
        return new RegisteredTool(definition, handler, timeout, untrustedOutput, true, definitionWithoutWorkspace);
    }

    /**
     * This tool, with {@code parameters} (optional, top-level ones such as a {@code save_as} path)
     * left out of its schema when the loop has no workspace, and its description replaced by
     * {@code descriptionWithoutThem} — which shouldn't mention them.
     *
     * @throws IllegalArgumentException if a parameter isn't in the schema or is required
     */
    public RegisteredTool withWorkspaceParameters(Collection<String> parameters, String descriptionWithoutThem) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(descriptionWithoutThem, "descriptionWithoutThem");
        Map<String, Object> schema = definition.getParameters() == null ? Map.of() : definition.getParameters();
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> p ? copy(p) : new LinkedHashMap<>();
        List<?> required = schema.get("required") instanceof List<?> r ? r : List.of();
        for (String parameter : parameters) {
            if (properties.remove(parameter) == null) {
                throw new IllegalArgumentException(definition.getName() + " has no parameter " + parameter);
            }
            if (required.contains(parameter)) {
                throw new IllegalArgumentException(definition.getName() + "'s " + parameter
                        + " is required; use withRequiresWorkspace() for a tool that can't work without a workspace");
            }
        }
        Map<String, Object> stripped = new LinkedHashMap<>(schema);
        stripped.put("properties", properties);
        ToolDefinition without = ToolDefinition.builder()
                .name(definition.getName())
                .description(descriptionWithoutThem)
                .parameters(stripped)
                .build();
        return new RegisteredTool(definition, handler, timeout, untrustedOutput, requiresWorkspace, without);
    }

    /** What the model is offered, given whether the loop has a workspace; empty if the tool isn't offered at all. */
    public Optional<ToolDefinition> definitionFor(boolean hasWorkspace) {
        if (hasWorkspace) {
            return Optional.of(definition);
        }
        if (requiresWorkspace) {
            return Optional.empty();
        }
        return Optional.of(definitionWithoutWorkspace != null ? definitionWithoutWorkspace : definition);
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
