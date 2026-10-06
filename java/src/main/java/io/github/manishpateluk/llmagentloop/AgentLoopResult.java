package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmagentloop.execution.Execution;

import io.github.manishpateluk.llmagentloop.usage.UsageTotals;

import java.util.List;
import java.util.Map;

/**
 * What a {@link LoopRequest#onResult()} callback receives: the final {@code llm-router}
 * {@link Response}, the full {@link Execution} trace of every step taken to produce it, and the
 * workspace files the run created, changed or deleted — so the implementor knows which documents
 * to hand back to its user.
 *
 * @param changedFiles normalized workspace paths this run wrote or deleted, in first-touched
 *                     order; read them from the same {@code Workspace} and scope to get their content
 * @param usage        every model call this run made, summed — tokens and estimated cost — including
 *                     planning, answer formatting, history compression and conversation compaction
 * @param structuredAnswer the final answer as data matching {@code LoopRequest.answerSchema}; {@code null}
 *                     when no schema was requested, the run stopped early, or the conversion failed
 *                     (a warning says so)
 */
public record AgentLoopResult(Response finalResponse, Execution execution, List<String> changedFiles,
                              UsageTotals usage, Map<String, Object> structuredAnswer) {

    public AgentLoopResult {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        usage = usage == null ? UsageTotals.ZERO : usage;
    }
}
