package io.github.manishpateluk.llmagentloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.ToolCall;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.execution.Execution;
import io.github.manishpateluk.llmagentloop.execution.StepAction;
import io.github.manishpateluk.llmagentloop.execution.StepRecord;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.plan.Plan;
import io.github.manishpateluk.llmagentloop.plan.PlanStep;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;

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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The library's main entry point: turns a single {@link LoopRequest} into a completed task by
 * recursively calling an LLM (via the {@link LlmRouter} supplied at construction) — running
 * tools, checking for completion, and possibly branching into sub-tasks — until the goal is
 * satisfied or {@link AgentProfile#maxSteps()} is exceeded. Reporting is entirely through
 * callbacks; usage is always asynchronous, there is no blocking call.
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
 * <p>Known simplifications in this pass, called out rather than silently glossed over: branches
 * (sub-tasks, and a {@code Plan}'s parallel-grouped steps) execute sequentially, not concurrently;
 * an unregistered tool call ends the run via {@link LoopRequest#onError()} rather than being
 * handed back to the caller to resolve; and growing history is not yet run through
 * {@code HistoryCompressor} before each call, since that needs a known target model and this loop
 * doesn't pin one down ahead of a call — a follow-up once that's decided.
 */
public final class AgentLoop {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Turns end without calling any tool at all still get a final answer, rather than failing the run. */
    private static final String NO_TOOL_CALL_FALLBACK_NOTE =
            "Model responded without calling a tool; treating its response as the final answer.";

    private final LlmRouter router;
    private final ToolRegistry tools;
    private final MemoryStore memory;

    public AgentLoop(LlmRouter router) {
        this(router, new ToolRegistry());
    }

    public AgentLoop(LlmRouter router, ToolRegistry tools) {
        this(router, tools, MemoryStore.NONE);
    }

    public AgentLoop(LlmRouter router, ToolRegistry tools, MemoryStore memory) {
        this.router = Objects.requireNonNull(router, "router");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.memory = Objects.requireNonNull(memory, "memory");
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
     * One in-flight execution's state — created fresh per {@link #run(LoopRequest)} call and
     * confined to the single virtual thread {@link #execute()} runs on (branches execute
     * sequentially on that same thread this pass, so no synchronization is needed here).
     */
    private final class Run {

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
        private final List<StepRecord> steps = new ArrayList<>();
        private final Instant startedAt = Instant.now();
        private int nextThread = 1;
        private int stepCount = 0;
        private int accumulatedCostUsdCents = 0;

        private Run(LoopRequest request) {
            this.request = request;
            this.profile = request.agentProfile() != null ? request.agentProfile() : AgentProfile.DEFAULT;
            this.attachments = toAttachments(request.files());
        }

        void execute() {
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
            Response response = router.complete(req);
            checkBudgets(0, response);
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
            Response response = router.complete(req);
            checkBudgets(0, response);

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
            Response response = router.complete(req);
            checkBudgets(0, response);

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

        private StepOutcome executePlan(Plan plan, String systemInstructions, List<Message> history, int thread) {
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
                checkStepBudget();

                List<ToolDefinition> availableTools = new ArrayList<>(tools.definitions());
                availableTools.add(AgentLoopSchemas.REPORT_COMPLETE);
                if (allowSubTasks) {
                    availableTools.add(AgentLoopSchemas.SPAWN_SUB_TASK);
                }

                List<Message> effectiveHistory = new ArrayList<>(history);
                List<String> memoryHints = memory.recall(goal);
                if (!memoryHints.isEmpty()) {
                    effectiveHistory.add(Message.system("Relevant memory:\n- " + String.join("\n- ", memoryHints)));
                }

                Request req = Request.builder()
                        .prompt(history.isEmpty() ? goal : "Continue toward the goal: " + goal)
                        .systemInstructions(systemInstructions)
                        .history(List.copyOf(effectiveHistory))
                        .attachments(attachments)
                        .tools(List.copyOf(availableTools))
                        .build();
                Response response = router.complete(req);
                checkBudgets(thread, response);

                Optional<ToolCall> complete = findToolCall(response, AgentLoopSchemas.REPORT_COMPLETE_TOOL);
                if (complete.isPresent()) {
                    String finalAnswer = String.valueOf(
                            complete.get().getArguments().getOrDefault("finalAnswer", response.getContent()));
                    emit(thread, MessageType.INFO, "Goal complete.");
                    recordStep(thread, StepAction.COMPLETE, goal, null, null, finalAnswer, response);
                    return new StepOutcome(finalAnswer, response);
                }

                Optional<ToolCall> subTask = allowSubTasks
                        ? findToolCall(response, AgentLoopSchemas.SPAWN_SUB_TASK_TOOL)
                        : Optional.empty();
                if (subTask.isPresent()) {
                    String subGoal = String.valueOf(subTask.get().getArguments().get("goal"));
                    int subThread = nextThread++;
                    emit(thread, MessageType.PROGRESS, "Delegating sub-task: " + subGoal);
                    recordStep(thread, StepAction.SUB_TASK, goal, null, null, subGoal, response);

                    StepOutcome subOutcome =
                            runGoalDirected(subGoal, systemInstructions, new ArrayList<>(history), subThread, true);

                    history.add(assistantNoteFor(response, "spawning sub-task: " + subGoal));
                    history.add(Message.tool(subOutcome.finalAnswer()));
                    continue;
                }

                if (!response.getToolCalls().isEmpty()) {
                    for (ToolCall call : response.getToolCalls()) {
                        history.add(assistantNoteFor(response, "calling " + call.getName() + " with " + call.getArguments()));

                        Optional<RegisteredTool> registered = tools.find(call.getName());
                        if (registered.isEmpty()) {
                            throw new UnregisteredToolException(call.getName());
                        }

                        emit(thread, MessageType.TOOL_CALL, "Calling tool: " + call.getName());
                        String result = registered.get().handler().handle(call.getArguments());
                        emit(thread, MessageType.TOOL_RESULT, "Tool " + call.getName() + " returned a result.");
                        recordStep(thread, StepAction.TOOL_CALL, goal, call.getName(), result, null, response);
                        history.add(Message.tool(result));
                    }
                    continue;
                }

                // Defensive fallback: some models won't reliably call report_complete even when instructed to.
                emit(thread, MessageType.WARNING, NO_TOOL_CALL_FALLBACK_NOTE);
                recordStep(thread, StepAction.COMPLETE, goal, null, null, response.getContent(), response);
                return new StepOutcome(response.getContent(), response);
            }
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
                emit(thread, MessageType.WARNING, "Stopping early: cost limit reached");
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
            request.onResult().accept(new AgentLoopResult(finalResponse, execution));
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

    /**
     * Since {@code llm-router}'s {@code Message} shape has no slot for a tool-call request itself
     * (only plain role+content), this folds what the assistant decided to do into its own history
     * turn so the flattened history stays coherent even when the model returned no accompanying text.
     */
    private static Message assistantNoteFor(Response response, String action) {
        String content = response.getContent();
        String prefix = (content == null || content.isBlank()) ? "" : content + "\n\n";
        return Message.assistant(prefix + "[" + action + "]");
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
