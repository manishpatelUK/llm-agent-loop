package io.github.manishpateluk.llmagentloop.tool;

import com.manishpateluk.llmrouter.model.ToolCall;

import java.util.Optional;

/**
 * The caller's chance to resolve a tool call the model made that isn't in the run's
 * {@link ToolRegistry} — e.g. by asking a human, or forwarding it to another service. Runs on the
 * run's own virtual thread, so it may block for as long as resolving the call takes.
 *
 * <p>A present result is fed back to the model as that call's tool result and the run carries on
 * as normal; an empty one ends the run with an {@code UnregisteredToolException} via
 * {@code onError}, as does {@link #NONE}, the default.
 */
@FunctionalInterface
public interface UnregisteredToolHandler {

    UnregisteredToolHandler NONE = call -> Optional.empty();

    Optional<String> handle(ToolCall call);
}
