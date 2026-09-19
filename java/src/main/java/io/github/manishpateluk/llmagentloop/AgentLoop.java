package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Response;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The library's main entry point: turns a single {@link LoopRequest} into a completed task by
 * driving one or more calls to an LLM (via the {@link LlmRouter} supplied at construction),
 * reporting progress, results, and errors entirely through callbacks — usage is always
 * asynchronous, there is no synchronous/blocking call.
 *
 * <p>Every overload of {@link #run} funnels into {@link #run(LoopRequest)}; the shorter overloads
 * just build a {@link LoopRequest} with sensible defaults (no {@link AgentProfile}, no files, a
 * no-op {@link MessageType} callback) so simple call sites don't need to touch the builder.
 *
 * <p>Execution itself is implemented in a later pass — for now {@link #run(LoopRequest)} defines
 * the entry point's shape and validates its input.
 */
public final class AgentLoop {

    private final LlmRouter router;

    public AgentLoop(LlmRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /** Runs {@code prompt} with no agent profile, no files, and no status-message callback. */
    public void run(String prompt, Consumer<Response> onResult, Consumer<Throwable> onError) {
        run(LoopRequest.builder()
                .prompt(prompt)
                .onResult(onResult)
                .onError(onError)
                .build());
    }

    /** Same as {@link #run(String, Consumer, Consumer)}, additionally reporting status updates via {@code onMessage}. */
    public void run(
            String prompt, Consumer<Response> onResult, Consumer<Throwable> onError, Consumer<AgentMessage> onMessage) {
        run(LoopRequest.builder()
                .prompt(prompt)
                .onResult(onResult)
                .onError(onError)
                .onMessage(onMessage)
                .build());
    }

    /**
     * The canonical entry point: every other {@code run} overload delegates here.
     *
     * @throws UnsupportedOperationException always, for now — loop execution lands in a later pass
     */
    public void run(LoopRequest request) {
        Objects.requireNonNull(request, "request");
        throw new UnsupportedOperationException("AgentLoop execution is not implemented yet"); //todo
    }
}
