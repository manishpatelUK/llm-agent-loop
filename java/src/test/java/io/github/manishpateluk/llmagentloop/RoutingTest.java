package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.config.FallbackPolicy;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.MODEL;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static org.assertj.core.api.Assertions.assertThat;

class RoutingTest {

    private static final RouterConfig PINNED = RouterConfig.builder()
            .route(List.of(RouteEntry.of(Provider.ANTHROPIC, MODEL)))
            .thinkingLevel(ThinkingLevel.HIGH)
            .temperature(0.2)
            .build();

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void theProfilesRoutingIsUsedAndTheLoopAddsToolSupportOnTop() {
        AgentLoop loop = model.loop().build();

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(profile(PlanMode.RECURSIVE_ON_EACH_STEP, PINNED)));

        RouterConfig sent = model.requests.getFirst().getConfig();
        assertThat(sent.getRoute()).containsExactly(RouteEntry.of(Provider.ANTHROPIC, MODEL));
        assertThat(sent.getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(sent.getTemperature()).isEqualTo(0.2);
        assertThat(sent.getRequiredFeatures()).contains(Feature.TOOLS);
    }

    @Test
    void aRequestsRoutingOverridesTheProfiles() {
        AgentLoop loop = model.loop().build();
        RouterConfig cheap = RouterConfig.builder().thinkingLevel(ThinkingLevel.LOW).build();

        loop.runAndWait(LoopRequest.builder().prompt("go").routerConfig(cheap)
                .agentProfile(profile(PlanMode.RECURSIVE_ON_EACH_STEP, PINNED)));

        RouterConfig sent = model.requests.getFirst().getConfig();
        assertThat(sent.getThinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(sent.getRoute()).isNull();
    }

    @Test
    void housekeepingCallsKeepTheRouteButAreCostOptimized() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> request.getPrompt().contains("Does accomplishing this require")
                ? Response.builder().content("{\"needsPlan\":false,\"reason\":\"simple\"}").build()
                : complete("done"));

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(profile(PlanMode.AUTO, PINNED)));

        Request planCheck = model.requests.getFirst();
        assertThat(planCheck.getConfig().isCostOptimized()).isTrue();
        assertThat(planCheck.getConfig().getRoute()).containsExactly(RouteEntry.of(Provider.ANTHROPIC, MODEL));
        assertThat(model.requests.get(1).getConfig().isCostOptimized()).isFalse();
    }

    @Test
    void theLoopsOwnAdjustmentsKeepEveryOtherRouterConfigField() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> request.getPrompt().contains("Does accomplishing this require")
                ? Response.builder().content("{\"needsPlan\":false,\"reason\":\"simple\"}").build()
                : complete("done"));
        RouterConfig finishing = PINNED.toBuilder().fallback(FallbackPolicy.FINISH).sendThinkingLevel(true).build();

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(profile(PlanMode.AUTO, finishing)));

        assertThat(model.requests).hasSizeGreaterThan(1).allSatisfy(request -> {
            assertThat(request.getConfig().getFallback()).isEqualTo(FallbackPolicy.FINISH);
            assertThat(request.getConfig().isSendThinkingLevel()).isTrue();
            assertThat(request.getConfig().getTemperature()).isEqualTo(0.2);
        });
        assertThat(model.requests.getFirst().getConfig().isCostOptimized()).isTrue();             // housekeeping
        assertThat(model.requests.get(1).getConfig().getRequiredFeatures()).contains(Feature.TOOLS); // working step
    }

    @Test
    void anAdjusterAppliesToTheRunsRoutingEvenWhenNoneIsSet() {
        AgentLoop loop = model.loop().build();

        loop.runAndWait(LoopRequest.builder().prompt("go")
                .agentProfile(profile(PlanMode.RECURSIVE_ON_EACH_STEP, PINNED))
                .routerConfigAdjuster(config -> config.toBuilder().fallback(FallbackPolicy.FINISH).build()));
        RouterConfig adjusted = model.requests.getFirst().getConfig();
        assertThat(adjusted.getFallback()).isEqualTo(FallbackPolicy.FINISH);
        assertThat(adjusted.getRoute()).containsExactly(RouteEntry.of(Provider.ANTHROPIC, MODEL));
        assertThat(adjusted.getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);

        model.requests.clear();
        loop.runAndWait(LoopRequest.builder().prompt("go")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build())
                .routerConfigAdjuster(config -> config.toBuilder().costOptimized(true).build()));
        assertThat(model.requests.getFirst().getConfig().isCostOptimized()).isTrue();
    }

    @Test
    void neverPlanCallsUseTheRoutingAsIs() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> Response.builder().content("hello").build());

        loop.runAndWait(LoopRequest.builder().prompt("hi").agentProfile(profile(PlanMode.NEVER_PLAN, PINNED)));

        assertThat(model.requests.getFirst().getConfig().getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(model.requests.getFirst().getConfig().getRequiredFeatures()).doesNotContain(Feature.TOOLS);
    }

    private static AgentProfile profile(PlanMode mode, RouterConfig config) {
        return AgentProfile.builder().planMode(mode).routerConfig(config).build();
    }
}
