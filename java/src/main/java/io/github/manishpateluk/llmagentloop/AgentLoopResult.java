package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmagentloop.execution.Execution;

/**
 * What a {@link LoopRequest#onResult()} callback receives: the final {@code llm-router}
 * {@link Response}, plus the full {@link Execution} trace of every step taken to produce it.
 */
public record AgentLoopResult(Response finalResponse, Execution execution) {
}
