package io.github.manishpateluk.llmagentloop.tool;

import com.manishpateluk.llmrouter.model.ToolDefinition;

/** A tool as registered on a {@link ToolRegistry}: its definition (told to the model) plus its local implementation. */
public record RegisteredTool(ToolDefinition definition, ToolHandler handler) {
}
