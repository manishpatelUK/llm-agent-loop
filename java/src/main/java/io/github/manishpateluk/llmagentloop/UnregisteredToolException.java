package io.github.manishpateluk.llmagentloop;

/**
 * Ends a run (via {@link LoopRequest#onError()}) when the model requests a tool that isn't
 * registered on the {@link AgentLoop} and {@link LoopRequest#onUnregisteredTool()} didn't resolve
 * it either — which is always the case with the default handler.
 */
public class UnregisteredToolException extends RuntimeException {

    UnregisteredToolException(String toolName) {
        super("Model requested unregistered tool: " + toolName);
    }
}
