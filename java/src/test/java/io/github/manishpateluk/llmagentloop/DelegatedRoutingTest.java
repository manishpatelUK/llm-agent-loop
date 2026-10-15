package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.FallbackPolicy;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.run;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.lastToolResult;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/** Delegated runs keep their own route and thinking level but take the delegating run's routing preferences. */
class DelegatedRoutingTest {

    private static final String ASSISTANT_MODEL = "test-delegating-assistant";
    private static final String SPECIALIST_MODEL = "test-delegated-specialist";

    private static final RouterConfig SPECIALIST_ROUTING = RouterConfig.builder()
            .route(List.of(RouteEntry.of(Provider.ANTHROPIC, SPECIALIST_MODEL)))
            .thinkingLevel(ThinkingLevel.MAX)
            .temperature(0.1)
            .build();

    private final List<Request> specialistRequests = new CopyOnWriteArrayList<>();
    private final List<Request> assistantRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void registerModels() {
        for (String model : List.of(ASSISTANT_MODEL, SPECIALIST_MODEL)) {
            ModelCapabilityTable.registerModel(ModelEntry.builder()
                    .provider(Provider.ANTHROPIC).model(model)
                    .contextWindowTokens(100_000).maxOutputTokens(4_000)
                    .supportsTools(true).supportsStructuredOutput(true)
                    .build());
        }
    }

    @AfterEach
    void removeModels() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, ASSISTANT_MODEL);
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, SPECIALIST_MODEL);
    }

    private AgentLoop assistantDelegatingTo(AgentProfile specialistProfile) {
        AgentLoop specialist = loop(request -> {
            specialistRequests.add(request);
            return complete("specialist answer");
        }, new ToolRegistry());
        return loop(request -> {
            assistantRequests.add(request);
            return lastToolResult(request) == null
                    ? toolCall("d1", "delegate_to_agent", Map.of("agent", "finance", "task", "Check the runway"))
                    : complete("done");
        }, new ToolRegistry().register(DelegationTools.delegateToAgent(List.of(
                AgentDelegate.of("finance", "Finance specialist", specialist, specialistProfile)))));
    }

    @Test
    void aDelegateKeepsItsRouteAndThinkingLevelButTakesTheRunsCostFallbackAndRequiredFeatures() throws InterruptedException {
        AgentLoop assistant = assistantDelegatingTo(
                AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).routerConfig(SPECIALIST_ROUTING).build());
        RouterConfig founderPreferences = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, ASSISTANT_MODEL)))
                .thinkingLevel(ThinkingLevel.LOW)
                .costOptimized(true)
                .fallback(FallbackPolicy.FINISH)
                .sendThinkingLevel(true)
                .requiredFeatures(Set.of(Feature.RESPONSE_SCHEMA))
                .build();

        AgentLoopRunSupport.Capture capture = run(assistant, LoopRequest.builder().prompt("how long is our runway?")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build())
                .routerConfig(founderPreferences));

        assertThat(capture.error()).isNull();
        assertThat(lastToolResult(assistantRequests.getLast())).isEqualTo("specialist answer");
        assertThat(specialistRequests).isNotEmpty().allSatisfy(request -> {
            RouterConfig config = request.getConfig();
            assertThat(config.getRoute()).containsExactly(RouteEntry.of(Provider.ANTHROPIC, SPECIALIST_MODEL));
            assertThat(config.getThinkingLevel()).isEqualTo(ThinkingLevel.MAX);
            assertThat(config.getTemperature()).isEqualTo(0.1);
            assertThat(config.isCostOptimized()).isTrue();
            assertThat(config.getFallback()).isEqualTo(FallbackPolicy.FINISH);
            assertThat(config.isSendThinkingLevel()).isTrue();
            assertThat(config.getRequiredFeatures()).contains(Feature.RESPONSE_SCHEMA);
        });
    }

    @Test
    void anAttachmentRequirementCarriesDownToDelegates() throws InterruptedException {
        AgentLoop assistant = assistantDelegatingTo(
                AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).routerConfig(SPECIALIST_ROUTING).build());

        run(assistant, LoopRequest.builder().prompt("go")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build())
                .routerConfig(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, ASSISTANT_MODEL))).build())
                .requireAttachmentSupport(true));

        assertThat(specialistRequests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.getConfig().getRequiredFeatures()).contains(Feature.ATTACHMENTS));
    }

    @Test
    void theRunsAdjusterAppliesToDelegatesToo() throws InterruptedException {
        AgentLoop assistant = assistantDelegatingTo(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build());

        run(assistant, LoopRequest.builder().prompt("go")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build())
                .routerConfigAdjuster(config -> config.toBuilder().fallback(FallbackPolicy.FINISH).costOptimized(true).build()));

        assertThat(assistantRequests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.getConfig().getFallback()).isEqualTo(FallbackPolicy.FINISH));
        assertThat(specialistRequests).isNotEmpty().allSatisfy(request -> {
            assertThat(request.getConfig().getFallback()).isEqualTo(FallbackPolicy.FINISH);
            assertThat(request.getConfig().isCostOptimized()).isTrue();
        });
    }

    @Test
    void withNoRoutingOnTheDelegatingRunADelegateRoutesExactlyAsItsProfileSays() throws InterruptedException {
        AgentLoop assistant = assistantDelegatingTo(
                AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).routerConfig(SPECIALIST_ROUTING).build());

        run(assistant, LoopRequest.builder().prompt("go")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build()));

        assertThat(specialistRequests).isNotEmpty().allSatisfy(request -> {
            RouterConfig config = request.getConfig();
            assertThat(config.toBuilder().requiredFeatures(SPECIALIST_ROUTING.getRequiredFeatures()).build())
                    .isEqualTo(SPECIALIST_ROUTING); // only the loop's own tool requirement added
        });
    }

    private static AgentLoop loop(Function<Request, Response> responder, ToolRegistry tools) {
        return AgentLoop.builder()
                .router(new LlmRouter(List.of(new FakeProviderAdapter(responder))))
                .tools(tools)
                .build();
    }
}
