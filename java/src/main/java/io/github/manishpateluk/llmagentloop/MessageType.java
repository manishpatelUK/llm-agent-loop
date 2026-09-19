package io.github.manishpateluk.llmagentloop;

/**
 * Category of a status update sent to a {@link LoopRequest#onMessage()} callback while an
 * {@link AgentLoop} run is in progress — e.g. to drive a "thinking..." indicator on a frontend.
 */
public enum MessageType {

    /** The loop is reasoning/working with no more specific update to report yet. */
    THINKING,

    /** A general progress note, e.g. moving on to the next step of a plan. */
    PROGRESS,

    /** A registered or caller-handled tool is about to be called. */
    TOOL_CALL,

    /** A tool call finished and returned a result. */
    TOOL_RESULT,

    /** Non-fatal, e.g. a structural API failure that's about to be retried. */
    WARNING,

    /** Any other informational update. */
    INFO
}
