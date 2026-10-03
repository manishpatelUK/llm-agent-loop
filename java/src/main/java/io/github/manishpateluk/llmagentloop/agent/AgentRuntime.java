package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.skill.Skill;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs {@link Agent}s: one runtime per process holds the shared infrastructure — the router,
 * memory store, workspace and any tools every agent gets — and runs any agent for any user. Each
 * call names the user via a {@link Scope}, so the same agent works on the right user's memory and
 * files every time; managing which session a user is in stays with the implementor.
 *
 * <pre>{@code
 * AgentRuntime runtime = new AgentRuntime(AgentLoop.builder()
 *         .memory(myMemoryStore)
 *         .workspace(myWorkspace)
 *         .build());
 *
 * Agent cofounder = Agent.builder(Files.readString(Path.of("agents/cofounder.md")))
 *         .skills(Skills.memory(), Skills.files(), Skills.spreadsheets(), Skills.dataAnalysis())
 *         .delegateTo(legalAgent)
 *         .build();
 *
 * runtime.run(cofounder, userMessage, Scope.of(tenantId, userId, sessionId),
 *         result -> reply(result.finalResponse().getContent()),
 *         error -> reportFailure(error));
 * }</pre>
 *
 * <p>Each agent gets the base loop's tools, plus its skills' tools, its own tools, and — if it has
 * delegates — {@code delegate_to_agent}; later ones win on a name clash. Delegated agents run on
 * this same runtime, with the same scope.
 */
public final class AgentRuntime {

    private final AgentLoop base;
    private final Map<Agent, AgentLoop> loops = new ConcurrentHashMap<>();

    /** {@code base} supplies the router, memory, workspace and settings, plus tools shared by every agent. */
    public AgentRuntime(AgentLoop base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    /** Runs {@code agent} asynchronously for {@code scope}'s user, reporting through the callbacks. */
    public void run(Agent agent, String prompt, Scope scope, Consumer<AgentLoopResult> onResult, Consumer<Throwable> onError) {
        run(agent, LoopRequest.builder().prompt(prompt).scope(scope).onResult(onResult).onError(onError));
    }

    /**
     * Runs {@code agent} asynchronously with full control over the request (files, status
     * messages, cost and time bounds...). The agent's profile replaces any {@code agentProfile}
     * set on the builder.
     */
    public void run(Agent agent, LoopRequest.LoopRequestBuilder request) {
        loopFor(agent).run(request.agentProfile(agent.profile()).build());
    }

    /** Runs {@code agent} on the calling thread and returns its result; failures are thrown. */
    public AgentLoopResult runAndWait(Agent agent, String prompt, Scope scope) {
        return runAndWait(agent, LoopRequest.builder().prompt(prompt).scope(scope));
    }

    /** {@link #runAndWait(Agent, String, Scope)} with full control over the request; callbacks on the builder are replaced. */
    public AgentLoopResult runAndWait(Agent agent, LoopRequest.LoopRequestBuilder request) {
        return loopFor(agent).runAndWait(request.agentProfile(agent.profile()));
    }

    /** {@code agent} as something other agents (or your own tools) can delegate to. */
    public AgentDelegate delegate(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        return new AgentDelegate() {
            @Override
            public String name() {
                return agent.name();
            }

            @Override
            public String description() {
                return agent.description().isBlank() ? "The " + agent.name() + " agent." : agent.description();
            }

            @Override
            public String run(String task, ToolContext context) {
                // Resolved per call, so agents can delegate to each other in either order of construction.
                return AgentDelegate.of(agent.name(), description(), loopFor(agent), agent.profile()).run(task, context);
            }
        };
    }

    /** The loop {@code agent} runs on: built once, then reused for every run of it. */
    AgentLoop loopFor(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        AgentLoop existing = loops.get(agent);
        if (existing != null) {
            return existing;
        }
        // Built outside computeIfAbsent: building may recurse into loopFor for delegate agents.
        AgentLoop built = base.withTools(registryFor(agent));
        AgentLoop raced = loops.putIfAbsent(agent, built);
        return raced != null ? raced : built;
    }

    private ToolRegistry registryFor(Agent agent) {
        ToolRegistry registry = new ToolRegistry().registerAll(base.tools().all());
        for (Skill skill : agent.skills()) {
            registry.registerAll(skill.tools());
        }
        registry.registerAll(agent.tools());

        List<AgentDelegate> delegates = new ArrayList<>(agent.delegates());
        for (Agent other : agent.delegateAgents()) {
            delegates.add(delegate(other));
        }
        if (!delegates.isEmpty()) {
            registry.register(DelegationTools.delegateToAgent(delegates));
        }
        return registry;
    }
}
