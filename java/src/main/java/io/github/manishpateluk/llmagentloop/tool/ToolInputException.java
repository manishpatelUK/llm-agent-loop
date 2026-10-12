package io.github.manishpateluk.llmagentloop.tool;

/**
 * Thrown by a {@link ToolHandler} when a call can't be carried out for a reason the model can
 * correct — a missing or malformed argument, a file that doesn't exist, a limit reached. The loop
 * returns {@code "Error: " + message} to the model as that call's result and the run continues,
 * so the model can try again differently. It's the tool's own call: the loop never retries a tool
 * itself. Any other exception from a handler also comes back to the model, as
 * {@code "Error: <tool> failed: <exception class>: <message>"}, but is reported to
 * {@code ToolInterceptor.failed} as a failure — throw this one when the model is meant to fix it.
 */
public class ToolInputException extends RuntimeException {

    public ToolInputException(String message) {
        super(message);
    }
}
