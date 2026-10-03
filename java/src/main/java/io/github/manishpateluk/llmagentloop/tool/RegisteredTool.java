package io.github.manishpateluk.llmagentloop.tool;

import com.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.Objects;

/** A tool as registered on a {@link ToolRegistry}: its definition (told to the model) plus its local implementation. */
public record RegisteredTool(ToolDefinition definition, ToolHandler handler) {

    public RegisteredTool {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
    }
}
