package io.github.manishpateluk.llmagentloop.execution;

import com.manishpateluk.llmrouter.model.Response;

import lombok.Builder;

import java.time.Instant;

/**
 * One recorded step of an {@link Execution}.
 *
 * @param thread      which branch this step belongs to (0 is the run's main thread)
 * @param stepIndex   global ordinal across the whole execution, for chronological ordering across threads
 * @param description what this step was trying to do
 * @param action      what it actually did; see {@link StepAction}
 * @param toolName    the tool called, when {@code action} is {@link StepAction#TOOL_CALL}
 * @param toolResult  that tool's result, when {@code action} is {@link StepAction#TOOL_CALL}
 * @param finalAnswer the thread's answer, when {@code action} is {@link StepAction#COMPLETE}
 * @param response    the raw {@code llm-router} response for this step's call
 * @param timestamp   when this step was recorded; defaults to {@link Instant#now()} if not supplied
 */
@Builder
public record StepRecord(
        int thread,
        int stepIndex,
        String description,
        StepAction action,
        String toolName,
        String toolResult,
        String finalAnswer,
        Response response,
        Instant timestamp) {

    public StepRecord {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
