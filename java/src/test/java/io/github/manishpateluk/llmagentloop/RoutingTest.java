package io.github.manishpateluk.llmagentloop;

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
