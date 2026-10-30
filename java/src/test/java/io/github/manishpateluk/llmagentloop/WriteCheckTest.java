package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.UntrustedContentGuard;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.manishpateluk.llmagentloop.testing.MockModel.lastToolResult;
import static org.assertj.core.api.Assertions.assertThat;

class WriteCheckTest {

    @Test
    void theGuardHoldsBackAnyCallItsToolSaysWritesOnceOutsideContentWasRead() {
        MockModel model = new MockModel()
                .callTool("read_page", Map.of())
                .callTool("ledger", Map.of("action", "view"))
                .callTool("ledger", Map.of("action", "post"))
                .callTool("broken", Map.of())
                .reply("done");
        ToolRegistry tools = new ToolRegistry()
                .register(new RegisteredTool(definition("read_page"), (args, context) -> "page text").withUntrustedOutput())
                .register(new RegisteredTool(definition("ledger"), (args, context) -> "ran " + args.get("action"))
                        .withWriteCheck((args, context) -> "post".equals(args.get("action"))))
                .register(new RegisteredTool(definition("broken"), (args, context) -> "ran")
                        .withWriteCheck((args, context) -> {
                            throw new IllegalStateException("store down");
                        }));

        TestRuns.run(model.loopBuilder().tools(tools).toolInterceptor(UntrustedContentGuard.block()).build(),
                LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(lastToolResult(model.requests().get(2))).contains("ran view");
        assertThat(lastToolResult(model.requests().get(3))).contains("This action wasn't approved");
        assertThat(lastToolResult(model.requests().get(4))).contains("This action wasn't approved");  // a failing check counts as a write
    }

    @Test
    void withersKeepTheWriteCheckAndAContextWithoutARunSaysNothingWrites() {
        RegisteredTool tool = new RegisteredTool(definition("t"), (args, context) -> "")
                .withWriteCheck((args, context) -> true)
                .withTimeout(Duration.ofSeconds(5))
                .withUntrustedOutput()
                .withRequiresWorkspace();
        Scope scope = Scope.forUser("u", "s");
        ToolContext detached = new ToolContext(UUID.randomUUID(), 0, scope, MemoryStore.NONE.scopedTo(scope),
                new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));

        assertThat(tool.writes(Map.of(), detached)).isTrue();
        assertThat(new RegisteredTool(definition("t"), (args, context) -> "").writes(Map.of(), detached)).isFalse();
        assertThat(detached.writes(ToolCall.builder().id("1").name("t").arguments(Map.of()).build())).isFalse();
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description(name)
                .parameters(Map.of("type", "object", "properties", Map.of(), "required", List.of())).build();
    }
}
