package io.github.manishpateluk.llmagentloop;

/**
 * Receives a run's answer as it's written, for showing it to the user in real time — set with
 * {@code LoopRequest.builder().answerStream(...)}.
 *
 * <p>An agent works in steps, and it can't be known in advance whether a step will produce the
 * answer or call a tool. So the text of each user-facing step streams as it's generated, and if
 * the step turns out to call tools after all (its text was a preamble like "Let me look that
 * up..."), {@link #onDiscard()} says to drop what was shown; the next step streams afresh. When
 * the run finishes, the text streamed since the last discard is the final answer — the same text
 * as {@code AgentLoopResult.finalResponse().getContent()}.
 *
 * <p>Only user-facing steps stream: not sub-tasks, not a plan's intermediate steps. Calls are
 * made on the run's thread.
 */
@FunctionalInterface
public interface AnswerStream {

    /** The next piece of the answer, in order. Never empty. */
    void onText(String delta);

    /** Everything streamed since the last discard isn't the answer after all; clear it. */
    default void onDiscard() {
    }
}
