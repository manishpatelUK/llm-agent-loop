package io.github.manishpateluk.llmagentloop;

import java.util.UUID;

/**
 * A run started with {@link AgentLoop#run(LoopRequest)}: lets the caller stop it — the user
 * pressed "stop", closed the chat, or sent a new message that supersedes it.
 */
public interface RunHandle {

    /** Matches the run's {@code Execution.id()} and every {@code AgentMessage} it sends. */
    UUID executionId();

    /**
     * Asks the run to stop. It stops at the next safe point — before its next model call or tool
     * call — and the thread is interrupted so a blocking model call, tool, or {@code ask_human} wait
     * ends early too. The run then finishes through {@code onResult} (not {@code onError}) with
     * {@code TerminationReason.CANCELLED} and whatever answer it had. Cancelling a finished run, or
     * cancelling twice, does nothing.
     */
    void cancel();

    boolean isCancelled();

    /** Whether the run has finished, by any route (completed, stopped early, cancelled or failed). */
    boolean isDone();
}
