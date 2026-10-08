package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Reloading agent definitions: the runtime's loop cache neither grows nor keeps stale loops. */
class AgentRuntimeCacheTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");

    private static Agent agent(String name, String instructions, Agent... delegates) {
        Agent.Builder builder = Agent.builder("---\nname: " + name + "\ndescription: The " + name
                + " agent.\nplan_mode: recursive\n---\n" + instructions);
        for (Agent delegate : delegates) {
            builder.delegateTo(delegate);
        }
        return builder.build();
    }

    @Test
    void aRebuiltAgentWithTheSameNameReplacesTheCachedLoop() {
        AgentRuntime runtime = new AgentRuntime(new MockModel().loopBuilder().build());
        Agent before = agent("writer", "Version 1.");
        Agent after = agent("writer", "Version 2.");

        var first = runtime.loopFor(before);
        assertThat(runtime.loopFor(before)).isSameAs(first);
        var second = runtime.loopFor(after);

        assertThat(second).isNotSameAs(first);
        assertThat(runtime.loopFor(after)).isSameAs(second);
        assertThat(runtime.cachedAgents()).isEqualTo(1);
    }

    @Test
    void reloadingEveryAgentManyTimesKeepsOneCachedLoopPerName() {
        MockModel model = new MockModel();
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        for (int reload = 0; reload < 50; reload++) {
            Agent research = agent("research", "Research, version " + reload + ".");
            Agent gtm = agent("gtm", "Go to market, version " + reload + ".");
            Agent chat = agent("chat", "Chat, version " + reload + ".", research, gtm);
            model.callTool(DelegationTools.DELEGATE, Map.of("agent", "research", "task", "Look into it"))
                    .reply("Findings v" + reload)
                    .reply("Answer v" + reload);

            AgentLoopResult result = runtime.runAndWait(chat, "go", ALICE);

            assertThat(result.finalResponse().getContent()).isEqualTo("Answer v" + reload);
            // The delegate that ran is the current version, not a stale cached one.
            assertThat(model.requests().get(model.requests().size() - 2).getSystemInstructions())
                    .startsWith("Research, version " + reload + ".");
        }
        assertThat(runtime.cachedAgents()).isEqualTo(2); // chat and research; gtm never ran
    }

    @Test
    void forgetAndClearDropCachedLoopsIncludingDelegates() {
        MockModel model = new MockModel()
                .callTool(DelegationTools.DELEGATE, Map.of("agent", "research", "task", "Look into it"))
                .reply("Findings")
                .reply("Answer");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        Agent research = agent("research", "Research.");
        Agent chat = agent("chat", "Chat.", research);
        runtime.runAndWait(chat, "go", ALICE);
        assertThat(runtime.cachedAgents()).isEqualTo(2);

        assertThat(runtime.forget(research)).isTrue();
        assertThat(runtime.forget("research")).isFalse();
        assertThat(runtime.cachedAgents()).isEqualTo(1);

        runtime.clear();
        assertThat(runtime.cachedAgents()).isZero();
    }

    @Test
    void aRunInFlightFinishesNormallyWhenItsLoopIsDroppedOrReplaced() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RegisteredTool wait = new RegisteredTool(ToolDefinition.builder().name("wait").description("Waits")
                .parameters(Map.of("type", "object")).build(), (args, context) -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "waited";
        });
        MockModel model = new MockModel().callTool("wait", Map.of()).reply("Finished.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        Agent original = Agent.builder("---\nname: worker\nplan_mode: recursive\n---\nWork.").tool(wait).build();

        AtomicReference<AgentLoopResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        runtime.run(original, LoopRequest.builder().prompt("go").scope(ALICE)
                .onResult(r -> {
                    result.set(r);
                    done.countDown();
                })
                .onError(e -> {
                    error.set(e);
                    done.countDown();
                }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        runtime.loopFor(Agent.builder("---\nname: worker\nplan_mode: recursive\n---\nWork, v2.").build());
        runtime.clear();
        release.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(result.get().finalResponse().getContent()).isEqualTo("Finished.");
        assertThat(runtime.cachedAgents()).isZero();
    }
}
