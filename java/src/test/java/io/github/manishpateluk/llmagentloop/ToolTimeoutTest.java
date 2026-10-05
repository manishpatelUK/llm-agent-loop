package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolHandler;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.HumanTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.lastToolResult;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolTimeoutTest {

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void aToolThatOverrunsTheDefaultTimeoutIsStoppedAndTheModelCarriesOn() {
        AtomicBoolean interrupted = new AtomicBoolean();
        AgentLoop loop = model.loop().toolTimeout(Duration.ofMillis(100))
                .tools(new ToolRegistry().register(new RegisteredTool(definition("hang"), sleeping(60_000, interrupted)))).build();
        model.respond(request -> lastToolResult(request) == null ? toolCall("h", "hang", Map.of()) : complete("worked around it"));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(lastToolResult(model.requests.get(1))).startsWith("Error: hang didn't finish within 100 ms");
        assertThat(interrupted).isTrue();
        assertThat(result.finalResponse().getContent()).isEqualTo("worked around it");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aToolsOwnTimeoutOverridesTheDefault() {
        AgentLoop loop = model.loop().toolTimeout(Duration.ofMillis(50))
                .tools(new ToolRegistry().register(new RegisteredTool(definition("slowish"), sleeping(300, new AtomicBoolean()))
                        .withTimeout(Duration.ofSeconds(10))))
                .build();
        model.respond(request -> lastToolResult(request) == null ? toolCall("s", "slowish", Map.of()) : complete("ok"));

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("finished");
    }

    @Test
    void aToolCannotRunPastTheRunsMaxDuration() {
        AgentLoop loop = model.loop()
                .tools(new ToolRegistry().register(new RegisteredTool(definition("hang"), sleeping(60_000, new AtomicBoolean())))).build();
        model.respond(request -> toolCall("h", "hang", Map.of()));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)
                .maxDuration(Duration.ofMillis(300)));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.TIME_LIMIT_REACHED);
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void fastToolsAreUnaffected() {
        AgentLoop loop = model.loop().toolTimeout(Duration.ofSeconds(5))
                .tools(new ToolRegistry().register(definition("quick"), (args, context) -> "instant")).build();
        model.respond(request -> lastToolResult(request) == null ? toolCall("q", "quick", Map.of()) : complete("ok"));

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("instant");
    }

    @Test
    void waitingToolsCarryLongTimeoutsAndTimeoutsMustBePositive() {
        assertThat(HumanTools.askHuman((q, c) -> Optional.empty()).timeout()).isEqualTo(HumanTools.TIMEOUT);
        assertThat(DelegationTools.delegateToAgent(List.of(new io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate() {
            public String name() {
                return "x";
            }

            public String description() {
                return "x";
            }

            public String run(String task, io.github.manishpateluk.llmagentloop.tool.ToolContext context) {
                return "";
            }
        })).timeout()).isEqualTo(DelegationTools.TIMEOUT);
        assertThatThrownBy(() -> new RegisteredTool(definition("x"), (a, c) -> "", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentLoop.builder().toolTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ToolHandler sleeping(long millis, AtomicBoolean interrupted) {
        return (args, context) -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IllegalStateException("interrupted", e);
            }
            return "finished";
        };
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description(name).parameters(Map.of("type", "object")).build();
    }
}
