package io.github.manishpateluk.llmagentloop;

/**
 * Thrown when the model requests a tool that isn't registered on this {@link AgentLoop}.
 *
 * <p>Per the library's design, an unregistered tool call is meant to be handed back to the
 * caller to resolve rather than failing outright — that bidirectional handoff (letting the
 * caller supply a result and resume the run) is a follow-up piece of work in its own right. For
 * now, an unregistered tool call ends the run with this exception via {@link LoopRequest#onError()}.
 */
public class UnregisteredToolException extends RuntimeException {

    UnregisteredToolException(String toolName) {
        super("Model requested unregistered tool: " + toolName);
    }
}
