package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.RunHandle;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.skill.Skill;
import io.github.manishpateluk.llmagentloop.skill.SkillLoader;
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
 * Agent assistant = Agent.builder(Files.readString(Path.of("agents/assistant.md")))
 *         .skills(Skills.memory(), Skills.files(), Skills.spreadsheets(), Skills.dataAnalysis())
 *         .delegateTo(legalAgent)
 *         .build();
 *
 * runtime.run(assistant, userMessage, Scope.of(tenantId, userId, sessionId),
 *         result -> reply(result.finalResponse().getContent()),
 *         error -> reportFailure(error));
 * }</pre>
 *
 * <p>Each agent gets the base loop's tools, plus its skills' tools (inlined and on-demand), its own
 * tools, {@code load_skill} if it has on-demand skills, and {@code delegate_to_agent} if it has
 * delegates; later ones win on a name clash. Delegated agents run on this same runtime, with the
 * same scope.
 *
 * <p><b>Reloading definitions.</b> The runtime caches one loop per agent <em>name</em>. Running a
 * different {@code Agent} instance with the same name (say, rebuilt after its Markdown changed)
 * replaces the cached loop, so rebuilding every agent on a reload neither grows the cache nor keeps
 * stale loops alive; delegates are resolved by the delegating agent's own {@code Agent} instances.
 * {@link #forget} and {@link #clear} drop cached loops explicitly, e.g. for agents that were removed.
 * All of this is safe while runs are in flight: a run that already has its loop finishes on it.
 */
public final class AgentRuntime {

    private final AgentLoop base;
    /** A cached loop and the exact {@code Agent} instance it was built for. */
    private record Cached(Agent agent, AgentLoop loop) {
    }

    private final Map<String, Cached> loops = new ConcurrentHashMap<>();

    /** {@code base} supplies the router, memory, workspace and settings, plus tools shared by every agent. */
    public AgentRuntime(AgentLoop base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    /** Runs {@code agent} asynchronously for {@code scope}'s user, reporting through the callbacks; the handle cancels it. */
    public RunHandle run(Agent agent, String prompt, Scope scope, Consumer<AgentLoopResult> onResult, Consumer<Throwable> onError) {
        return run(agent, LoopRequest.builder().prompt(prompt).scope(scope).onResult(onResult).onError(onError));
    }

    /**
     * Runs {@code agent} asynchronously with full control over the request (files, status
     * messages, cost and time bounds...). The agent's profile replaces any {@code agentProfile}
     * set on the builder.
     */
    public RunHandle run(Agent agent, LoopRequest.LoopRequestBuilder request) {
        return loopFor(agent).run(request.agentProfile(agent.profile()).build());
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

    /**
     * Drops the cached loop for {@code agent}'s name, whichever instance it was built for. The next
     * run of an agent with that name builds a fresh one. Returns whether anything was cached.
     */
    public boolean forget(Agent agent) {
        return forget(Objects.requireNonNull(agent, "agent").name());
    }

    /** Drops the cached loop for the agent called {@code name}; see {@link #forget(Agent)}. */
    public boolean forget(String name) {
        return loops.remove(Objects.requireNonNull(name, "name")) != null;
    }

    /** Drops every cached loop, delegates' included. Runs in flight finish on the loops they have. */
    public void clear() {
        loops.clear();
    }

    /** How many agents currently have a cached loop. */
    public int cachedAgents() {
        return loops.size();
    }

    /**
     * The loop {@code agent} runs on: built once per instance, then reused for every run of it.
     * A different instance with the same name replaces it.
     */
    AgentLoop loopFor(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        Cached existing = loops.get(agent.name());
        if (existing != null && existing.agent() == agent) {
            return existing.loop();
        }
        // Built outside compute: building may recurse into loopFor for delegate agents.
        Cached built = new Cached(agent, base.withTools(registryFor(agent)));
        Cached winner = loops.compute(agent.name(),
                (name, current) -> current != null && current.agent() == agent ? current : built);
        return winner.loop();
    }

    private ToolRegistry registryFor(Agent agent) {
        ToolRegistry registry = new ToolRegistry().registerAll(base.tools().all());
        for (Skill skill : agent.skills()) {
            registry.registerAll(skill.tools());
        }
        // On-demand skills' tools are registered up front: only their instructions wait for load_skill.
        for (Skill skill : agent.onDemandSkills()) {
            registry.registerAll(skill.tools());
        }
        if (!agent.onDemandSkills().isEmpty()) {
            registry.register(SkillLoader.loadSkill(agent.onDemandSkills()));
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
