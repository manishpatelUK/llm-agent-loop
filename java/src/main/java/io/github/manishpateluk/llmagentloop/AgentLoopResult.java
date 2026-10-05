package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmagentloop.execution.Execution;

import java.util.List;

/**
 * What a {@link LoopRequest#onResult()} callback receives: the final {@code llm-router}
 * {@link Response}, the full {@link Execution} trace of every step taken to produce it, and the
 * workspace files the run created, changed or deleted — so the implementor knows which documents
 * to hand back to its user.
 *
 * @param changedFiles normalized workspace paths this run wrote or deleted, in first-touched
 *                     order; read them from the same {@code Workspace} and scope to get their content
 */
public record AgentLoopResult(Response finalResponse, Execution execution, List<String> changedFiles) {

    public AgentLoopResult {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    }
}
