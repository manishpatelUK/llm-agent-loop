package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.RequestInterceptor;
import com.manishpateluk.llmrouter.config.Feature;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.ToolCall;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;
import com.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.compression.CompressionAttempt;
import io.github.manishpateluk.llmagentloop.compression.CompressionExhaustedException;
import io.github.manishpateluk.llmagentloop.compression.CompressionListener;
import io.github.manishpateluk.llmagentloop.compression.CompressionMethod;
import io.github.manishpateluk.llmagentloop.compression.CompressionOutcome;
import io.github.manishpateluk.llmagentloop.compression.HistoryCompressor;
import io.github.manishpateluk.llmagentloop.execution.Execution;
import io.github.manishpateluk.llmagentloop.execution.StepAction;
import io.github.manishpateluk.llmagentloop.execution.StepRecord;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.memory.ScopedMemory;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.ScopedWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.Workspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import io.github.manishpateluk.llmagentloop.plan.Plan;
import io.github.manishpateluk.llmagentloop.plan.PlanStep;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The library's main entry point: turns a single {@link LoopRequest} into a completed task by
 * recursively calling an LLM (via the {@link LlmRouter} supplied at construction) — running
 * tools, checking for completion, and possibly branching into sub-tasks — until the goal is
 * satisfied or {@link AgentProfile#maxSteps()} is exceeded — every LLM call the run makes (the
 * {@code AUTO} plan check and plan generation included) counts as a step. Reporting is entirely through
 * callbacks; usage is always asynchronous, there is no blocking call.
 *
 * <p><b>{@link #builder()} is the recommended way to construct one</b> — it assembles a router
 * for you (with automatic history compression on by default, via
 * {@code HistoryCompressor.newSelfCompressingRouter}) from either explicit provider adapters or
 * environment-auto-detected credentials. The constructors below remain for callers that already
 * have a fully-assembled {@link LlmRouter} — e.g. one with its own {@code RequestInterceptor} for
 * something other than compression — and want no factory logic in the way.
 *
 * <p>Every overload of {@link #run} funnels into {@link #run(LoopRequest)}; the shorter overloads
 * just build a {@link LoopRequest} with sensible defaults (no {@link AgentProfile}, no files, a
 * no-op status-message callback).
 *
 * <p>{@link LoopRequest#maxCostUsdCents()} and {@link LoopRequest#maxDuration()} are optional,
 * approximate bounds on top of {@link AgentProfile#maxSteps()}: checked after each step completes
 * (not mid-step), so once either is met or exceeded the run stops and returns whatever answer it
 * has so far — via {@link LoopRequest#onResult()}, not {@link LoopRequest#onError()} — rather than
 * continuing to the next step. {@code Execution.terminationReason()} on the result says whether
 * that happened.
 *
 * <p>When the router compresses history (see {@code HistoryCompressor.newSelfCompressingRouter}),
 * each compression is reported on this run's {@code onMessage} and recorded in its
 * {@code Execution} as a {@code HISTORY_COMPRESSION} step, via {@code HistoryCompressor.withListener}.
 *
 * <p>A tool call the model makes that isn't registered is handed to
 * {@link LoopRequest#onUnregisteredTool()}; if that doesn't resolve it, the run ends via
 * {@link LoopRequest#onError()} with an {@link UnregisteredToolException}.
 *
 * <p>Known simplification in this pass, called out rather than silently glossed over: branches
 * (sub-tasks, and a {@code Plan}'s parallel-grouped steps) execute sequentially, not concurrently.
 */
public final class AgentLoop {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    /** Lenient: structured output carries fields the records don't (e.g. a plan's {@code summary}), and models add extras. */
    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Turns end without calling any tool at all still get a final answer, rather than failing the run. */
    private static final String NO_TOOL_CALL_FALLBACK_NOTE =
            "Model responded without calling a tool; treating its response as the final answer.";

    /** How many memories the loop recalls into each step's context, unprompted. */
    private static final int AUTO_RECALL_LIMIT = 5;

    /** Every call that offers tools — including the loop's own {@code report_complete} — only routes to models that can call them. */
    private static final RouterConfig TOOLS_REQUIRED =
            RouterConfig.builder().requiredFeatures(Set.of(Feature.TOOLS)).build();

    private final LlmRouter router;
    private final ToolRegistry tools;
    private final MemoryStore memory;
    private final ScopeLevel memoryLevel;
    private final Workspace workspace;
    private final ScopeLevel workspaceLevel;
    private final WorkspaceLimits workspaceLimits;

    public AgentLoop(LlmRouter router) {
        this(router, new ToolRegistry());
    }

    public AgentLoop(LlmRouter router, ToolRegistry tools) {
        this(router, tools, MemoryStore.NONE);
    }

    public AgentLoop(LlmRouter router, ToolRegistry tools, MemoryStore memory) {
        this(router, tools, memory, ScopeLevel.USER, Workspace.NONE, ScopeLevel.USER, WorkspaceLimits.DEFAULT);
    }

    private AgentLoop(
            LlmRouter router, ToolRegistry tools, MemoryStore memory, ScopeLevel memoryLevel,
            Workspace workspace, ScopeLevel workspaceLevel, WorkspaceLimits workspaceLimits) {
        this.router = Objects.requireNonNull(router, "router");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.memoryLevel = Objects.requireNonNull(memoryLevel, "memoryLevel");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.workspaceLevel = Objects.requireNonNull(workspaceLevel, "workspaceLevel");
        this.workspaceLimits = Objects.requireNonNull(workspaceLimits, "workspaceLimits");
    }

    /**
     * A loop sharing this one's router, memory, workspace and their settings, but offering
     * {@code tools} instead — how one process runs many agents with different tool sets over the
     * same infrastructure (see {@code AgentRuntime}).
     */
    public AgentLoop withTools(ToolRegistry tools) {
        return new AgentLoop(router, tools, memory, memoryLevel, workspace, workspaceLevel, workspaceLimits);
    }

    /** The tools this loop offers. */
    public ToolRegistry tools() {
        return tools;
    }

    /** See {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles an {@link AgentLoop}, including — unlike the constructors — its {@link LlmRouter}.
     * Specify at most one of {@link #adapters} or {@link #router}; with neither, credentials are
     * auto-detected from the environment (same as {@code new LlmRouter()}).
     *
     * <p>History compression is on by default (via {@code HistoryCompressor.newSelfCompressingRouter}),
     * using {@code HistoryCompressor.DEFAULT_METHODS} unless {@link #compressionMethods} overrides
     * them. It only applies when this builder assembles the router itself — {@link #compress} and
     * {@link #compressionMethods} have no effect together with {@link #router}, since a
     * caller-supplied router's compression story (if any) was already decided when it was built.
     */
    public static final class Builder {

        private List<ProviderAdapter> adapters;
        private LlmRouter router;
        private boolean compress = true;
        private List<CompressionMethod> compressionMethods;
        private ToolRegistry tools = new ToolRegistry();
        private MemoryStore memory = MemoryStore.NONE;
        private ScopeLevel memoryLevel = ScopeLevel.USER;
        private Workspace workspace = Workspace.NONE;
        private ScopeLevel workspaceLevel = ScopeLevel.USER;
        private WorkspaceLimits workspaceLimits = WorkspaceLimits.DEFAULT;

        private Builder() {
        }

        /** Explicit adapters the assembled router should use — mutually exclusive with {@link #router}. */
        public Builder adapters(List<ProviderAdapter> adapters) {
            this.adapters = adapters;
            return this;
        }

        /** A fully-assembled router to use as-is — mutually exclusive with {@link #adapters}. */
        public Builder router(LlmRouter router) {
            this.router = router;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        /** Long-term memory, e.g. an {@code InMemoryMemoryStore} or your own; defaults to {@link MemoryStore#NONE}. */
        public Builder memory(MemoryStore memory) {
            this.memory = memory;
            return this;
        }

        /** How widely memory is shared across {@link Scope}s; defaults to {@link ScopeLevel#USER}. */
        public Builder memoryLevel(ScopeLevel memoryLevel) {
            this.memoryLevel = memoryLevel;
            return this;
        }

        /** File storage for the workspace tools, e.g. an {@code InMemoryWorkspace} or your own; defaults to {@link Workspace#NONE}. */
        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        /** How widely workspace files are shared across {@link Scope}s; defaults to {@link ScopeLevel#USER}. */
        public Builder workspaceLevel(ScopeLevel workspaceLevel) {
            this.workspaceLevel = workspaceLevel;
            return this;
        }

        /** Caps on each scope's workspace; defaults to {@link WorkspaceLimits#DEFAULT}. */
        public Builder workspaceLimits(WorkspaceLimits workspaceLimits) {
            this.workspaceLimits = workspaceLimits;
            return this;
        }

        /** History compression is on by default; pass {@code false} to disable it. */
        public Builder compress(boolean compress) {
            this.compress = compress;
            if (!compress) {
                this.compressionMethods = null;
            }
            return this;
        }

        /** Enables compression (if {@link #compress} disabled it) using this preference list instead of the default. */
        public Builder compressionMethods(List<CompressionMethod> methods) {
            this.compress = true;
            this.compressionMethods = methods;
            return this;
        }

        public AgentLoop build() {
            if (adapters != null && router != null) {
                throw new IllegalStateException("Specify at most one of adapters() or router()");
            }
            if (router != null && compressionMethods != null) {
                throw new IllegalStateException("compressionMethods() has no effect on a caller-supplied router() "
                        + "— build it with HistoryCompressor.newSelfCompressingRouter(...) yourself instead");
            }

            LlmRouter effectiveRouter = router != null ? router : buildRouter();
            return new AgentLoop(effectiveRouter, tools, memory, memoryLevel, workspace, workspaceLevel, workspaceLimits);
        }

        private LlmRouter buildRouter() {
            if (!compress) {
                return adapters != null ? new LlmRouter(adapters) : new LlmRouter();
            }
            if (compressionMethods != null) {
                return selfCompressingRouterWithCustomMethods();
            }
            return adapters != null
                    ? HistoryCompressor.newSelfCompressingRouter(adapters)
                    : HistoryCompressor.newSelfCompressingRouter();
        }

        /**
         * {@code HistoryCompressor} only exposes a default-methods/auto-detect-adapters overload
         * family (deliberately, to avoid an ambiguous overload distinguished only by a generic
         * type parameter); building this one combination — a caller-supplied method list with
         * auto-detected adapters — needs the same self-reference trick inline instead.
         */
        private LlmRouter selfCompressingRouterWithCustomMethods() {
            AtomicReference<LlmRouter> self = new AtomicReference<>();
            RequestInterceptor interceptor = (provider, model, request) ->
                    HistoryCompressor.compress(request, provider, model, compressionMethods, self.get()).request();
            LlmRouter built = adapters != null ? new LlmRouter(adapters, interceptor) : new LlmRouter(interceptor);
            self.set(built);
            return built;
        }
    }

    /** Runs {@code prompt} with no agent profile, no files, and no status-message callback. */
    public void run(String prompt, Consumer<AgentLoopResult> onResult, Consumer<Throwable> onError) {
        run(LoopRequest.builder()
                .prompt(prompt)
                .onResult(onResult)
                .onError(onError)
                .build());
    }

    /** Same as {@link #run(String, Consumer, Consumer)}, additionally reporting status updates via {@code onMessage}. */
    public void run(
            String prompt, Consumer<AgentLoopResult> onResult, Consumer<Throwable> onError, Consumer<AgentMessage> onMessage) {
        run(LoopRequest.builder()
                .prompt(prompt)
                .onResult(onResult)
                .onError(onError)
                .onMessage(onMessage)
                .build());
    }

    /** The canonical entry point: every other {@code run} overload delegates here. */
    public void run(LoopRequest request) {
        Objects.requireNonNull(request, "request");
        EXECUTOR.submit(() -> new Run(request).execute());
    }

    /**
     * Runs to completion on the <em>calling</em> thread and returns the result — for callers that
     * are already on a thread they're happy to block (a virtual thread, a tool handler delegating to
     * another agent, a test). The builder's own {@code onResult}/{@code onError} are replaced;
     * {@code onMessage} and everything else is used as given.
     *
     * @throws RuntimeException whatever would have gone to {@code onError}; a checked exception is
     *                          wrapped in an {@link IllegalStateException}
     */
    public AgentLoopResult runAndWait(LoopRequest.LoopRequestBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        AtomicReference<AgentLoopResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        new Run(builder.onResult(result::set).onError(error::set).build()).execute();
        Throwable failure = error.get();
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
        return result.get();
    }

    /**
     * One in-flight execution's state — created fresh per {@link #run(LoopRequest)} call and
     * confined to the single virtual thread {@link #execute()} runs on (branches execute
     * sequentially on that same thread this pass, so no synchronization is needed here).
     */
    private final class Run implements CompressionListener {

        private final LoopRequest request;
        private final AgentProfile profile;

        /**
         * Read from {@link LoopRequest#files()} once and reused, unchanged, on every call this
         * run makes. Combined with reusing the same {@link LlmRouter} (and so the same adapter
         * instances) across those calls, this is exactly the pattern {@code llm-router} 1.0.2's
         * adapter-level content-hash dedup relies on: repeat calls with the same {@link Attachment}
         * bytes get uploaded once (via each provider's Files API) and referenced by id afterward,
         * rather than re-embedded every turn. Nothing else to do here to get that for free.
         */
        private final List<Attachment> attachments;

        private final UUID executionId = UUID.randomUUID();
        private final Scope scope;
        private final ScopedMemory scopedMemory;
        private final ScopedWorkspace scopedWorkspace;
        private final List<StepRecord> steps = new ArrayList<>();
        private final Instant startedAt = Instant.now();
        private int nextThread = 1;
        /** The thread whose LLM call is in flight — what any compression reported mid-call is attributed to. */
        private int currentThread = 0;
        private int stepCount = 0;
        private int accumulatedCostUsdCents = 0;

        private Run(LoopRequest request) {
            this.request = request;
            this.profile = request.agentProfile() != null ? request.agentProfile() : AgentProfile.DEFAULT;
            this.attachments = toAttachments(request.files());
            this.scope = request.scope() != null ? request.scope() : Scope.ephemeral(executionId);
            this.scopedMemory = memory.scopedTo(scope.atLevel(memoryLevel));
            this.scopedWorkspace = workspace.scopedTo(scope.atLevel(workspaceLevel), workspaceLimits);
        }

        void execute() {
            HistoryCompressor.withListener(this, () -> {
                executeInScope();
                return null;
            });
        }

        private void executeInScope() {
            try {
                if (profile.planMode() == PlanMode.NEVER_PLAN) {
                    runNeverPlan();
                    return;
                }

                String systemInstructions = profile.toSystemInstructionsFragment();
                List<Message> history = new ArrayList<>();

                boolean recursive = switch (profile.planMode()) {
                    case ALWAYS_PLAN -> false;
                    case RECURSIVE_ON_EACH_STEP -> true;
                    case AUTO -> !checkPlanNeeded(systemInstructions);
                    case NEVER_PLAN -> throw new IllegalStateException("unreachable");
                };

                StepOutcome outcome = recursive
                        ? runGoalDirected(request.prompt(), systemInstructions, history, 0, true)
                        : executePlan(generatePlan(systemInstructions, history), systemInstructions, history, 0);

                Response finalResponse = outcome.response().toBuilder().content(outcome.finalAnswer()).build();
                complete(finalResponse, TerminationReason.COMPLETED);
            } catch (BudgetExceeded e) {
                Response truncated = truncatedResponse(e.lastResponse);
                recordStep(e.thread, StepAction.TRUNCATED, "budget exceeded", null, null, truncated.getContent(), e.lastResponse);
                complete(truncated, e.reason);
            } catch (Exception e) {
                request.onError().accept(e);
            }
        }

        private void runNeverPlan() {
            emit(0, MessageType.THINKING, "Answering directly.");
            Request req = Request.builder()
                    .prompt(request.prompt())
                    .systemInstructions(profile.toSystemInstructionsFragment())
                    .attachments(attachments)
                    .build();
            Response response = call(0, req);
            recordStep(0, StepAction.COMPLETE, request.prompt(), null, null, response.getContent(), response);
            complete(response, TerminationReason.COMPLETED);
        }

        /** {@code AUTO} only: a cheap call deciding between {@code ALWAYS_PLAN} and {@code RECURSIVE_ON_EACH_STEP} behavior. */
        private boolean checkPlanNeeded(String systemInstructions) {
            Request req = Request.builder()
                    .prompt("Goal: " + request.prompt() + "\n\nDoes accomplishing this require breaking it into an "
                            + "explicit multi-step plan executed in order, or can it be pursued step-by-step, "
                            + "deciding the next action as you go?")
                    .systemInstructions(systemInstructions)
                    .attachments(attachments)
                    .responseSchema(AgentLoopSchemas.PLAN_NEEDED_SCHEMA)
                    .config(RouterConfig.builder().costOptimized(true).build())
                    .build();
            Response response = call(0, req);

            Map<String, Object> out = response.getStructuredOutput();
            boolean needsPlan = out != null && Boolean.TRUE.equals(out.get("needsPlan"));
            String reason = out != null ? String.valueOf(out.getOrDefault("reason", "")) : "";
            emit(0, MessageType.THINKING, reason.isBlank() ? "Deciding whether this needs a plan." : reason);
            recordStep(0, StepAction.PLAN_CHECK, request.prompt(), null, null, String.valueOf(needsPlan), response);
            return needsPlan;
        }

        private Plan generatePlan(String systemInstructions, List<Message> history) {
            String guidance = profile.planningGuidance().isEmpty()
                    ? ""
                    : "\n\nPlanning guidance:\n- " + String.join("\n- ", profile.planningGuidance());
            Request req = Request.builder()
                    .prompt("Goal: " + request.prompt() + guidance + "\n\nProduce an ordered list of steps to accomplish this goal.")
                    .systemInstructions(systemInstructions)
                    .history(List.copyOf(history))
                    .attachments(attachments)
                    .responseSchema(AgentLoopSchemas.PLAN_SCHEMA)
                    .build();
            Response response = call(0, req);

            Map<String, Object> out = response.getStructuredOutput();
            if (out == null) {
                throw new IllegalStateException("Plan generation returned no structured output");
            }
            Plan plan = JSON.convertValue(out, Plan.class);
            String summary = String.valueOf(out.getOrDefault("summary", ""));
            emit(0, MessageType.PROGRESS,
                    summary.isBlank() ? "Created a plan with " + plan.steps().size() + " step(s)." : summary);
            recordStep(0, StepAction.PLAN_CREATED, request.prompt(), null, null, summary, response);
            return plan;
        }

        /**
         * Steps share {@code history}, seeded with the overall goal (each step's own prompt is
         * only its description) and accumulating each completed step's answer, so later steps
         * build on earlier ones rather than starting blind.
         */
        private StepOutcome executePlan(Plan plan, String systemInstructions, List<Message> history, int thread) {
            history.add(Message.user("Overall goal: " + request.prompt()));
            StepOutcome last = null;
            for (PlanStep planStep : plan.steps()) {
                emit(thread, MessageType.PROGRESS, "Starting plan step: " + planStep.description());
                last = runGoalDirected(planStep.description(), systemInstructions, history, thread, false);
            }
            if (last == null) {
                throw new IllegalStateException("Plan had no steps");
            }
            return last;
        }

        /**
         * The step loop for a single thread pursuing {@code goal}: call the LLM, then either run a
         * tool (looping back), spawn a sub-task (looping back once it resolves), or complete.
         */
        private StepOutcome runGoalDirected(
                String goal, String systemInstructions, List<Message> history, int thread, boolean allowSubTasks) {
            emit(thread, MessageType.THINKING, "Working on: " + goal);

            while (true) {
                List<ToolDefinition> availableTools = new ArrayList<>(tools.definitions());
                availableTools.add(AgentLoopSchemas.REPORT_COMPLETE);
                if (allowSubTasks) {
                    availableTools.add(AgentLoopSchemas.SPAWN_SUB_TASK);
                }

                List<Message> effectiveHistory = new ArrayList<>(history);
                List<MemoryEntry> recalled = scopedMemory.search(goal, AUTO_RECALL_LIMIT);
                if (!recalled.isEmpty()) {
                    effectiveHistory.add(Message.system("Relevant memory:\n- " + String.join("\n- ",
                            recalled.stream().map(MemoryEntry::content).toList())));
                }

                Request req = Request.builder()
                        .prompt(history.isEmpty() ? goal : "Continue toward the goal: " + goal)
                        .systemInstructions(systemInstructions)
                        .history(List.copyOf(effectiveHistory))
                        .attachments(attachments)
                        .tools(List.copyOf(availableTools))
                        .config(TOOLS_REQUIRED)
                        .build();
                Response response = call(thread, req);

                Optional<ToolCall> complete = findToolCall(response, AgentLoopSchemas.REPORT_COMPLETE_TOOL);
                if (complete.isPresent()) {
                    warnIgnoredToolCalls(thread, response, complete.get());
                    String finalAnswer = String.valueOf(
                            complete.get().getArguments().getOrDefault("finalAnswer", response.getContent()));
                    emit(thread, MessageType.INFO, "Goal complete.");
                    recordStep(thread, StepAction.COMPLETE, goal, null, null, finalAnswer, response);
                    history.add(Message.assistant("Completed: " + goal + "\n\n" + finalAnswer));
                    return new StepOutcome(finalAnswer, response);
                }

                Optional<ToolCall> subTask = allowSubTasks
                        ? findToolCall(response, AgentLoopSchemas.SPAWN_SUB_TASK_TOOL)
                        : Optional.empty();
                if (subTask.isPresent()) {
                    warnIgnoredToolCalls(thread, response, subTask.get());
                    String subGoal = String.valueOf(subTask.get().getArguments().get("goal"));
                    int subThread = nextThread++;
                    emit(thread, MessageType.PROGRESS, "Delegating sub-task: " + subGoal);
                    recordStep(thread, StepAction.SUB_TASK, goal, null, null, subGoal, response);

                    StepOutcome subOutcome =
                            runGoalDirected(subGoal, systemInstructions, new ArrayList<>(history), subThread, true);

                    history.add(Message.assistant(response.getContent(), List.of(subTask.get())));
                    history.add(Message.tool(subTask.get().getId(), subOutcome.finalAnswer()));
                    continue;
                }

                if (!response.getToolCalls().isEmpty()) {
                    history.add(Message.assistant(response.getContent(), response.getToolCalls()));
                    for (ToolCall call : response.getToolCalls()) {
                        String result = runTool(thread, call);
                        emit(thread, MessageType.TOOL_RESULT, "Tool " + call.getName() + " returned a result.");
                        recordStep(thread, StepAction.TOOL_CALL, goal, call.getName(), result, null, response);
                        history.add(Message.tool(call.getId(), result));
                    }
                    continue;
                }

                // Defensive fallback: some models won't reliably call report_complete even when instructed to.
                emit(thread, MessageType.WARNING, NO_TOOL_CALL_FALLBACK_NOTE);
                recordStep(thread, StepAction.COMPLETE, goal, null, null, response.getContent(), response);
                return new StepOutcome(response.getContent(), response);
            }
        }

        /** A registered tool's handler, else the caller's {@link LoopRequest#onUnregisteredTool()}. */
        private String runTool(int thread, ToolCall call) {
            Optional<RegisteredTool> registered = tools.find(call.getName());
            if (registered.isPresent()) {
                emit(thread, MessageType.TOOL_CALL, "Calling tool: " + call.getName());
                ToolContext context = new ToolContext(executionId, thread, scope, scopedMemory, scopedWorkspace,
                        (type, message) -> emit(thread, type, message));
                try {
                    return registered.get().handler().handle(call.getArguments(), context);
                } catch (ToolInputException e) {
                    emit(thread, MessageType.WARNING, "Tool " + call.getName() + " reported: " + e.getMessage());
                    return "Error: " + e.getMessage();
                }
            }

            emit(thread, MessageType.TOOL_CALL, "Handing unregistered tool to the caller: " + call.getName());
            Optional<String> resolved = request.onUnregisteredTool().handle(call);
            if (resolved == null || resolved.isEmpty()) {
                throw new UnregisteredToolException(call.getName());
            }
            return resolved.get();
        }

        /** Every LLM call the run makes goes through here, so each one counts toward {@code maxSteps} and the budgets. */
        private Response call(int thread, Request req) {
            checkStepBudget();
            currentThread = thread;
            Response response = router.complete(req);
            checkBudgets(thread, response);
            return response;
        }

        /**
         * A control tool ({@code report_complete}/{@code spawn_sub_task}) is acted on alone; any
         * other calls in the same response are never executed, so say so rather than dropping them silently.
         */
        private void warnIgnoredToolCalls(int thread, Response response, ToolCall handled) {
            List<String> ignored = response.getToolCalls().stream()
                    .filter(call -> call != handled)
                    .map(ToolCall::getName)
                    .toList();
            if (!ignored.isEmpty()) {
                emit(thread, MessageType.WARNING, "Ignoring " + ignored.size() + " other tool call(s) requested alongside "
                        + handled.getName() + ": " + String.join(", ", ignored));
            }
        }

        @Override
        public void compressed(Provider provider, String model, CompressionOutcome outcome) {
            String methods = outcome.attempts().stream()
                    .filter(CompressionAttempt::succeeded)
                    .map(attempt -> attempt.method().name())
                    .collect(Collectors.joining(", "));
            String description = "Compressed history for " + provider + "/" + model + " from ~"
                    + outcome.originalEstimatedTokens() + " to ~" + outcome.finalEstimatedTokens()
                    + " tokens (target " + outcome.targetTokens() + ") via " + methods;
            emit(currentThread, MessageType.INFO, description);
            recordStep(currentThread, StepAction.HISTORY_COMPRESSION, description, null, null, null, null);
        }

        @Override
        public void exhausted(Provider provider, String model, CompressionExhaustedException failure) {
            String description = "Could not compress history to fit " + provider + "/" + model
                    + "; the router will try its next candidate, if any";
            emit(currentThread, MessageType.WARNING, description);
            recordStep(currentThread, StepAction.HISTORY_COMPRESSION, description, null, null, null, null);
        }

        private void checkStepBudget() {
            stepCount++;
            if (stepCount > profile.maxSteps()) {
                throw new AgentLoopStepLimitExceededException(profile.maxSteps());
            }
        }

        /**
         * Approximate, checked after {@code response} comes back rather than before the call that
         * produced it — so a step already in flight when a bound is crossed still completes, and
         * the bound is met "at or after", never exactly. Throws {@link BudgetExceeded} to unwind
         * every nested call (plan steps, sub-tasks) straight back to {@link #execute()} in one go.
         */
        private void checkBudgets(int thread, Response response) {
            if (response.getUsage() != null) {
                accumulatedCostUsdCents += response.getUsage().getEstimatedCostUsdCents();
            }

            Integer maxCost = request.maxCostUsdCents();
            if (maxCost != null && accumulatedCostUsdCents >= maxCost) {
                emit(thread, MessageType.WARNING, "Stopping early: cost limit reached.");
                throw new BudgetExceeded(thread, TerminationReason.COST_LIMIT_REACHED, response);
            }

            Duration maxDuration = request.maxDuration();
            if (maxDuration != null && Duration.between(startedAt, Instant.now()).compareTo(maxDuration) >= 0) {
                emit(thread, MessageType.WARNING, "Stopping early: time limit reached.");
                throw new BudgetExceeded(thread, TerminationReason.TIME_LIMIT_REACHED, response);
            }
        }

        private void emit(int thread, MessageType type, String message) {
            request.onMessage().accept(AgentMessage.of(executionId, thread, type, message));
        }

        private void recordStep(
                int thread, StepAction action, String description, String toolName, String toolResult,
                String finalAnswer, Response response) {
            steps.add(StepRecord.builder()
                    .thread(thread)
                    .stepIndex(steps.size())
                    .description(description)
                    .action(action)
                    .toolName(toolName)
                    .toolResult(toolResult)
                    .finalAnswer(finalAnswer)
                    .response(response)
                    .build());
        }

        private void complete(Response finalResponse, TerminationReason reason) {
            Execution execution = new Execution(executionId, List.copyOf(steps), reason);
            request.onResult().accept(new AgentLoopResult(finalResponse, execution, List.copyOf(scopedWorkspace.changedPaths())));
        }

        /** The best-effort "result as is" when a bound was hit mid-run: the last response's own content, if any. */
        private Response truncatedResponse(Response lastResponse) {
            String content = lastResponse.getContent();
            if (content == null || content.isBlank()) {
                content = "Stopped early before producing a final answer: a cost or time limit was reached.";
            }
            return lastResponse.toBuilder().content(content).build();
        }
    }

    private record StepOutcome(String finalAnswer, Response response) {
    }

    /**
     * Internal control-flow signal only — unwinds straight from wherever a bound was crossed
     * (however deep in nested plan-step/sub-task calls) back to {@link Run#execute()}, which
     * turns it into a graceful, non-error {@link AgentLoopResult}. Never surfaced to callers.
     */
    private static final class BudgetExceeded extends RuntimeException {
        private final int thread;
        private final TerminationReason reason;
        private final Response lastResponse;

        BudgetExceeded(int thread, TerminationReason reason, Response lastResponse) {
            super(null, null, false, false);
            this.thread = thread;
            this.reason = reason;
            this.lastResponse = lastResponse;
        }
    }

    private static Optional<ToolCall> findToolCall(Response response, String name) {
        return response.getToolCalls().stream().filter(call -> name.equals(call.getName())).findFirst();
    }

    private static List<Attachment> toAttachments(List<File> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        List<Attachment> result = new ArrayList<>(files.size());
        for (File file : files) {
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                String mediaType = Files.probeContentType(file.toPath());
                result.add(Attachment.builder()
                        .mediaType(mediaType == null ? "application/octet-stream" : mediaType)
                        .data(data)
                        .filename(file.getName())
                        .build());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read file: " + file, e);
            }
        }
        return List.copyOf(result);
    }
}
