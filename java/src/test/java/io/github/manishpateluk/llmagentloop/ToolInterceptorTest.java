package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolDecision;
import io.github.manishpateluk.llmagentloop.tool.ToolInterceptor;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.lastToolResult;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInterceptorTest {

    private final ScriptedModel model = new ScriptedModel();
    private final AtomicInteger payments = new AtomicInteger();
    private final AtomicReference<Map<String, Object>> paidWith = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void refusingAToolCallTellsTheModelAndTheToolNeverRuns() {
        AgentLoop loop = loop(new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                return call.getName().equals("pay") ? ToolDecision.refuse("The user declined this payment") : ToolDecision.proceed();
            }
        });
        model.respond(request -> lastToolResult(request) == null ? toolCall("p", "pay", Map.of("amount", 100)) : complete("ok"));

        AgentLoopRunSupport.Capture capture = run(loop);

        assertThat(payments).hasValue(0);
        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("Error: The user declined this payment");
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING && m.message().contains("refused"));
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("ok");
    }

    @Test
    void respondingShortCircuitsTheTool() {
        AgentLoop loop = loop(new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                return ToolDecision.respond("cached: paid");
            }
        });
        model.respond(request -> lastToolResult(request) == null ? toolCall("p", "pay", Map.of("amount", 100)) : complete("ok"));

        run(loop);

        assertThat(payments).hasValue(0);
        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("cached: paid");
    }

    @Test
    void proceedWithReplacesTheArguments() {
        AgentLoop loop = loop(new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                return ToolDecision.proceedWith(Map.of("amount", 50, "capped", true));
            }
        });
        model.respond(request -> lastToolResult(request) == null ? toolCall("p", "pay", Map.of("amount", 100)) : complete("ok"));

        run(loop);

        assertThat(paidWith.get()).containsEntry("amount", 50).containsEntry("capped", true);
    }

    @Test
    void afterCanRewriteResultsAndInterceptorsNestInOrder() {
        List<String> order = new CopyOnWriteArrayList<>();
        AgentLoop loop = loop(interceptor("outer", order), interceptor("inner", order), new ToolInterceptor() {
            @Override
            public String after(ToolCall call, String result, ToolContext context) {
                return result.replaceAll("\\d{4}-\\d{4}", "[redacted]");
            }
        });
        model.respond(request -> lastToolResult(request) == null ? toolCall("p", "pay", Map.of("amount", 1)) : complete("ok"));

        run(loop);

        assertThat(order).containsExactly("before outer", "before inner", "after inner", "after outer");
        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("paid with card [redacted]");
    }

    @Test
    void failedIsCalledWhenAToolBlowsUpAndTheFailureStillEndsTheRun() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        ToolRegistry tools = new ToolRegistry().register(definition("explode"), (args, context) -> {
            throw new IllegalStateException("database down");
        });
        AgentLoop loop = model.loop().tools(tools).toolInterceptor(new ToolInterceptor() {
            @Override
            public void failed(ToolCall call, Throwable error, ToolContext context) {
                seen.set(error);
            }
        }).build();
        model.respond(request -> toolCall("e", "explode", Map.of()));

        assertThatThrownBy(() -> loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)))
                .hasMessage("database down");
        assertThat(seen.get()).hasMessage("database down");
    }

    @Test
    void interceptorsAlsoSeeCallerResolvedUnregisteredTools() {
        List<String> names = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().toolInterceptor(new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                names.add(call.getName());
                return ToolDecision.proceed();
            }
        }).build();
        model.respond(request -> lastToolResult(request) == null ? toolCall("x", "ask_finance_team", Map.of()) : complete("ok"));

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)
                .onUnregisteredTool(call -> Optional.of("approved")));

        assertThat(names).containsExactly("ask_finance_team");
    }

    @Test
    void interceptorsCarryOverToLoopsMadeWithWithTools() {
        List<String> names = new CopyOnWriteArrayList<>();
        AgentLoop base = model.loop().toolInterceptor(new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                names.add(call.getName());
                return ToolDecision.proceed();
            }
        }).build();
        AgentLoop derived = base.withTools(payTools());
        model.respond(request -> lastToolResult(request) == null ? toolCall("p", "pay", Map.of("amount", 1)) : complete("ok"));

        derived.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(names).containsExactly("pay");
    }

    private AgentLoopRunSupport.Capture run(AgentLoop loop) {
        try {
            return AgentLoopRunSupport.run(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentLoop loop(ToolInterceptor... interceptors) {
        AgentLoop.Builder builder = model.loop().tools(payTools());
        for (ToolInterceptor interceptor : interceptors) {
            builder.toolInterceptor(interceptor);
        }
        return builder.build();
    }

    private ToolRegistry payTools() {
        return new ToolRegistry().register(definition("pay"), (args, context) -> {
            payments.incrementAndGet();
            paidWith.set(args);
            return "paid with card 1234-5678";
        });
    }

    private static ToolInterceptor interceptor(String name, List<String> order) {
        return new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                order.add("before " + name);
                return ToolDecision.proceed();
            }

            @Override
            public String after(ToolCall call, String result, ToolContext context) {
                order.add("after " + name);
                return result;
            }
        };
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description(name).parameters(Map.of("type", "object")).build();
    }
}
