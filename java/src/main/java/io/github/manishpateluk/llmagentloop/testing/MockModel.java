package io.github.manishpateluk.llmagentloop.testing;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A scripted stand-in for an LLM, for testing agents, skills and tools deterministically — no
 * network, no API keys, no cost. Script its replies in order, build an {@code AgentLoop} on it, run,
 * then assert on what the agent did and on every request the model received.
 *
 * <pre>{@code
 * MockModel model = new MockModel()
 *         .callTool("get_weather", Map.of("city", "Paris"))   // 1st call: the model calls a tool
 *         .reply("It's sunny in Paris.");                     // 2nd call: it answers
 * AgentLoop loop = model.loopBuilder().tools(myTools).build();
 *
 * TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Weather in Paris?")
 *         .agentProfile(MockModel.STEP_BY_STEP));
 * assertEquals("It's sunny in Paris.", run.answer());
 * assertEquals("18C", MockModel.lastToolResult(model.requests().get(1)));
 * }</pre>
 *
 * <p>Each model call takes the next scripted reply. Once the script runs out, the
 * {@link #otherwise fallback} answers (by default, the run fails with a clear message, so a test
 * never silently passes on an unscripted call). Use {@link #STEP_BY_STEP} as the agent profile
 * unless you're testing planning: {@code PlanMode.AUTO} first makes a plan-check call, which then
 * needs scripting too ({@link #planCheck}, {@link #plan}).
 *
 * <p>Streaming is simulated word by word. Every reply carries {@link #usage token usage} so
 * metering can be tested. Thread-safe: one model can serve concurrent runs, though their order of
 * consuming the script is then up to the scheduler.
 */
public final class MockModel {

    /** A profile that skips planning, so the script only covers working steps. */
    public static final io.github.manishpateluk.llmagentloop.AgentProfile STEP_BY_STEP =
            io.github.manishpateluk.llmagentloop.AgentProfile.builder()
                    .planMode(io.github.manishpateluk.llmagentloop.PlanMode.RECURSIVE_ON_EACH_STEP).build();

    private final Deque<Function<Request, Response>> script = new ArrayDeque<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolCallIds = new AtomicInteger();
    private Function<Request, Response> fallback = request -> {
        throw new IllegalStateException("MockModel ran out of scripted replies at call " + requests.size()
                + "; prompt was: " + request.getPrompt());
    };
    private int inputTokens = 10;
    private int outputTokens = 5;

    /** The next call replies with plain text — how the agent gives its final answer. */
    public synchronized MockModel reply(String text) {
        return respond(request -> Response.builder().content(text).build());
    }

    /** The next call requests one tool call. */
    public MockModel callTool(String name, Map<String, Object> arguments) {
        return callTools(Map.of(name, arguments));
    }

    /** The next call requests several tool calls in one turn (in the map's iteration order). */
    public synchronized MockModel callTools(Map<String, Map<String, Object>> calls) {
        Objects.requireNonNull(calls, "calls");
        return respond(request -> {
            List<ToolCall> toolCalls = new ArrayList<>();
            calls.forEach((name, args) -> toolCalls.add(ToolCall.builder()
                    .id("call_" + toolCallIds.incrementAndGet()).name(name).arguments(args).build()));
            return Response.builder().content("").toolCalls(toolCalls).build();
        });
    }

    /** The next call delegates a sub-goal to a sub-task. */
    public MockModel spawnSubTask(String goal) {
        return callTool("spawn_sub_task", Map.of("goal", goal));
    }

    /** For {@code PlanMode.AUTO}: the next call answers the loop's "does this need a plan?" check. */
    public MockModel planCheck(boolean needsPlan) {
        return respond(request -> Response.builder()
                .content("{\"needsPlan\":" + needsPlan + ",\"reason\":\"scripted\"}").build());
    }

    /** For planning runs: the next call returns a plan with these step descriptions. */
    public synchronized MockModel plan(String... steps) {
        StringBuilder json = new StringBuilder("{\"summary\":\"scripted plan\",\"steps\":[");
        for (int i = 0; i < steps.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"description\":").append(quote(steps[i])).append('}');
        }
        String content = json.append("]}").toString();
        return respond(request -> Response.builder().content(content).build());
    }

    /** The next call is answered by {@code responder}, which sees the full request. */
    public synchronized MockModel respond(Function<Request, Response> responder) {
        script.addLast(Objects.requireNonNull(responder, "responder"));
        return this;
    }

    /** Answers every call after the script runs out (by default, those calls fail the run). */
    public synchronized MockModel otherwise(Function<Request, Response> fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        return this;
    }

    /** Token counts reported on every reply (defaults: 10 in, 5 out). */
    public synchronized MockModel usage(int inputTokens, int outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        return this;
    }

    /** Every request the model received, in order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** How many scripted replies haven't been used yet. */
    public synchronized int remaining() {
        return script.size();
    }

    /** A router whose only provider is this model. */
    public LlmRouter router() {
        return new LlmRouter(List.of(new Adapter()));
    }

    /** {@code AgentLoop.builder()} already wired to {@link #router()}; add tools, memory etc. as usual. */
    public AgentLoop.Builder loopBuilder() {
        return AgentLoop.builder().router(router());
    }

    /** The most recent tool result in a request's history — what the model was told a tool returned. */
    public static String lastToolResult(Request request) {
        List<Message> history = request.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getRole() == Role.TOOL) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    /** Names of the tools offered to the model in a request. */
    public static List<String> toolsOffered(Request request) {
        return request.getTools().stream().map(t -> t.getName()).toList();
    }

    private Response next(Request request) {
        Function<Request, Response> responder;
        synchronized (this) {
            requests.add(request);
            responder = script.isEmpty() ? fallback : script.removeFirst();
        }
        Response response = responder.apply(request);
        return response.getUsage() != null ? response
                : response.toBuilder().usage(Usage.builder().inputTokens(inputTokens).outputTokens(outputTokens).build()).build();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** The provider seam: always available, answers from the script. */
    private final class Adapter implements ProviderAdapter {
        @Override
        public Provider id() {
            return Provider.ANTHROPIC;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Response send(String model, Request adaptedRequest) {
            return next(adaptedRequest);
        }

        @Override
        public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
            Response response = next(adaptedRequest);
            String content = response.getContent() == null ? "" : response.getContent();
            for (String piece : content.split("(?<= )")) {
                if (!piece.isEmpty()) {
                    onText.accept(piece);
                }
            }
            return response;
        }
    }
}
