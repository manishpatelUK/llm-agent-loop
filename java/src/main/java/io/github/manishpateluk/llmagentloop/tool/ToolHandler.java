package io.github.manishpateluk.llmagentloop.tool;

import java.util.Map;

/**
 * A registered tool's implementation: takes the model's parsed call arguments plus the
 * {@link ToolContext} of the run making the call, and returns a result string for the model.
 *
 * <p>Throw {@link ToolInputException} for a problem the model can fix (a bad argument, a missing
 * file) — its message goes back to the model as the result. Any other exception ends the run via
 * {@code onError}. Tools that don't need the context can be registered with
 * {@link ToolRegistry#register(com.manishpateluk.llmrouter.model.ToolDefinition, java.util.function.Function)}.
 */
@FunctionalInterface
public interface ToolHandler {

    String handle(Map<String, Object> arguments, ToolContext context);
}
