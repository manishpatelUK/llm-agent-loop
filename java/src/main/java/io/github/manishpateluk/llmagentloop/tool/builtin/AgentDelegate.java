package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouterConfig;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Another agent that {@code delegate_to_agent} can hand work to — e.g. a general assistant agent
 * delegating to a legal or finance specialist. {@link #of} wraps an {@link AgentLoop} and its
 * {@link AgentProfile}; implement this directly to delegate to something else entirely (a remote
 * agent service, a human team's queue).
 */
public interface AgentDelegate {

    /** How the model refers to this agent; {@code ^[a-zA-Z0-9_-]{1,64}$}. */
    String name();

    /** What this agent is good at — the model chooses delegates by it. */
    String description();

    /**
     * Carries out {@code task} for the user of the run in {@code context} and returns the result
     * as text. Blocks until done. Throw {@link ToolInputException} for a failure the delegating
     * agent could work around (e.g. by splitting the task).
     */
    String run(String task, ToolContext context);

    /**
     * Delegates to {@code loop} running {@code profile}. The delegated run uses the same
     * {@code Scope} as the delegating one — same user, same memory and workspace — and the same
     * {@code LoopRequest.operatingContext}, and its status updates are forwarded, prefixed with this
     * delegate's name. It has its own step limit ({@code profile.maxSteps()}) — reaching it, the
     * delegate wraps up with its best answer, which comes back marked as possibly incomplete — and
     * its model calls also count toward the delegating run's step, cost and time limits. When one of
     * those is reached, the delegated run stops and the delegating run winds down, once, for both.
     *
     * <p>The delegated run routes with {@code profile}'s own route and thinking level, but takes the
     * delegating run's routing preferences — {@code costOptimized}, {@code fallback},
     * {@code sendThinkingLevel} and its {@code requiredFeatures} (added to the profile's) — so a
     * run's choice of how costly to be and whether to step down rather than fail holds for the
     * specialists it hands work to. The delegating run's {@code LoopRequest.routerConfigAdjuster}
     * is passed on too, and applied after that.
     */
    static AgentDelegate of(String name, String description, AgentLoop loop, AgentProfile profile) {
        return of(name, description, loop, profile, true);
    }

    /**
     * {@link #of(String, String, AgentLoop, AgentProfile)}, choosing whether the delegated run
     * inherits the delegating run's operating context: {@code false} for delegates that should see
     * only the task, e.g. ones that shouldn't learn who the user is.
     */
    static AgentDelegate of(String name, String description, AgentLoop loop, AgentProfile profile,
                            boolean inheritOperatingContext) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(loop, "loop");
        return new AgentDelegate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String run(String task, ToolContext context) {
                AgentLoopResult result = loop.runAndWait(LoopRequest.builder()
                        .prompt(task)
                        .agentProfile(profile)
                        .routerConfig(inheritedRouting(profile == null ? null : profile.routerConfig(), context.routerConfig()))
                        .routerConfigAdjuster(context.routerConfigAdjuster())
                        .operatingContext(inheritOperatingContext ? context.operatingContext() : null)
                        .scope(context.scope())
                        .onMessage(message -> context.report(message.type(), "[" + name + "] " + message.message())));
                String answer = result.finalResponse().getContent();
                TerminationReason reason = result.execution().terminationReason();
                if (reason != TerminationReason.COMPLETED) {
                    context.report(MessageType.WARNING, "[" + name + "] stopped early: " + reason);
                    return "(" + name + " stopped early — " + reason + " — so this may be incomplete.)\n" + answer;
                }
                return answer;
            }
        };
    }

    /**
     * {@code own} routing (or the default) with the delegating run's routing preferences —
     * {@code costOptimized}, {@code fallback}, {@code sendThinkingLevel}, and its required features
     * added to {@code own}'s — while keeping {@code own}'s route, thinking level and sampling.
     * {@code own} unchanged when the delegating run has no routing of its own.
     */
    private static RouterConfig inheritedRouting(RouterConfig own, RouterConfig delegating) {
        if (delegating == null) {
            return own;
        }
        RouterConfig base = own != null ? own : RouterConfig.builder().build();
        Set<Feature> required = EnumSet.noneOf(Feature.class);
        required.addAll(base.getRequiredFeatures());
        required.addAll(delegating.getRequiredFeatures());
        return base.toBuilder()
                .costOptimized(delegating.isCostOptimized())
                .fallback(delegating.getFallback())
                .sendThinkingLevel(delegating.isSendThinkingLevel())
                .requiredFeatures(Set.copyOf(required))
                .build();
    }
}
