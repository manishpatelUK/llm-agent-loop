# llm-agent-loop

A library that provides the recursive "agent loop" needed to turn a single request into a
completed task by repeatedly calling an LLM, using tools, and adapting to failures along the
way. It sits on top of [`llm-router`](https://github.com/manishpatelUK/llm-router) for the
actual provider/model calls, and is meant to be the reusable core underneath many different
kinds of agents — from a chatbot backend to a fully autonomous "digital cofounder" that handles
tasks across admin, legal, and software development.

This is a multi-language project - one folder for each implementation, e.g. [java/](java/).

## What it does

A caller (a chatbot app, a workflow engine, another service) hands the looper a request. That
request can reference other assets — files, strings that represent other assets, tools the
caller wants made available, and callback hooks for things like progress updates, results, and
errors. From there the looper drives the work to completion without the caller needing to manage
the conversation with the LLM itself.

At a high level, one invocation of the looper:

1. **Decides whether — and how — to plan**, per the run's `AgentProfile.planMode`: always generate
   an explicit plan first, never plan (a single one-shot LLM call, e.g. for a plain chatbot
   reply), let each step figure out the next action for itself (and, recursively, spawn its own
   sub-tasks), or let a cheap runtime check decide between the first and third options per request.
2. **Executes step by step**, using `llm-router` for every LLM call. `llm-router` is configured
   with an ordered list of candidate models/providers and deterministically walks through them
   based on the capability table — the first one that can actually handle the call (given the
   tools, files, schema, etc. involved) is used. A step's own decision — call a tool, delegate a
   sub-task, or declare the goal complete — is made through `llm-router`'s native tool-calling
   support, not a bespoke protocol.
3. **Recovers from structural failures.** If a call fails for a fixable reason — an unsupported
   API setting, a tool/file/JSON-schema mismatch, etc. — the looper diagnoses the problem and
   retries with an adjusted call, rather than surfacing a raw provider error. This retry behavior
   is bounded: every invocation has cost/step constraints so recovery attempts can't run away.
4. **Calls registered tools.** Tools are registered on the looper in a structured way (a
   registered tool can be a simple functional callback with typed parameters). If the LLM asks to
   call a tool that isn't registered, that request is handed back to the caller to handle rather
   than failing silently.
5. **Context window aware.** When a conversation risks breaching the model's context window, the
   looper compresses history through an ordered list of named strategies — cheap, local, and
   algorithmic first (deduplication/whitespace cleanup, oldest-turn truncation, a hand-rolled
   TextRank extractive summarizer with no model to load), escalating to an LLM summarization call
   via `llm-router` only if those aren't enough. Each strategy is tried in order, like
   `llm-router`'s own provider fallback, until the request fits or every strategy is exhausted.
6. **Follows an agent profile.** Similar in spirit to Claude "skills," each looper run is given an
   `AgentProfile` describing the agent's overall behavior — its plan mode, standing goals,
   planning guidance, operating context, and a hard step-count cap — which gets serialized into
   the system instructions for every LLM call made during that run. This profile is intentionally
   generic so it can describe anything from a narrow support chatbot to a broad, autonomous
   cofounder-style agent.

The looper maintains conversation state using `llm-router`'s own model types (messages, requests,
responses, etc.) rather than duplicating them.

## Status

Early stage / under active design. This README will be kept up to date as each piece
(planning, tool registration, retry/recovery, history compression, agent profiles, etc.) is
implemented.

Implemented so far (Java):
- **Main entry point** (`io.github.manishpateluk.llmagentloop`) — `AgentLoop` is the library's
  entry point, constructed with an `LlmRouter` (plus an optional `ToolRegistry` and a `MemoryStore`
  placeholder) and run via `run(...)`, with progressively-defaulted overloads converging on the
  canonical `run(LoopRequest)`. `LoopRequest` carries the prompt, an optional `AgentProfile`,
  optional `File` attachments, and the run's three async callbacks: `onResult` (an
  `AgentLoopResult` — the final `llm-router` `Response` plus the full `Execution` trace),
  `onError`, and `onMessage` — status updates (`AgentMessage`: an execution id, a thread number, a
  `MessageType`, a message body, a timestamp, and free-form metadata) meant for things like a
  "thinking..." indicator on a frontend. Usage is always async — `run` returns immediately and the
  work happens on a virtual thread, reporting back entirely through those callbacks.
  - `AgentProfile.planMode` (see `PlanMode`) picks the run's shape: `NEVER_PLAN` bypasses the loop
    for a single one-shot call; `ALWAYS_PLAN` generates an explicit `Plan` (via structured output)
    and executes its steps in order; `RECURSIVE_ON_EACH_STEP` has no upfront plan — each step
    decides its own next action and may delegate a self-contained sub-goal to a new thread, whose
    result folds back in as context once it completes; `AUTO` (the default) makes a cheap
    structured-output call to decide between the first two per request.
  - A step's decision — call a tool, spawn a sub-task, or declare the goal complete — goes through
    `llm-router`'s native tool-calling (`Request.tools`/`Response.toolCalls`), including two
    synthetic control tools (`report_complete`, `spawn_sub_task`) the loop handles internally,
    rather than a separate custom structured-output protocol for step decisions.
  - Every step is recorded (`StepRecord`: thread, action, tool/result, response) into an
    `Execution`, keyed by a random execution id; branches (sub-tasks, and a `Plan`'s
    parallel-grouped steps) execute sequentially this pass, not concurrently.
  - `maxSteps` on `AgentProfile` (default 25, no way to request unbounded) is the safety net
    against runaway recursion until real cost/time budgets exist
    (`AgentLoopStepLimitExceededException`).
  - `LoopRequest.maxCostUsdCents` and `maxDuration` are optional, approximate per-run bounds —
    unlike `maxSteps`, they default to unbounded (current no-bound behavior) and, when set, stop
    the run *gracefully* rather than failing it: checked after each step completes (not mid-step,
    so the bound is met "at or after", never exact), the run returns whatever answer it has so far
    via `onResult` — not `onError` — with a `WARNING` `AgentMessage` explaining why, and
    `Execution.terminationReason()` (`COMPLETED` / `COST_LIMIT_REACHED` / `TIME_LIMIT_REACHED`) on
    the result records what happened. The check applies globally across the whole run — a bound
    crossed inside a sub-task or a plan step stops everything, not just that branch.
  - File attachments are read once per run and the same `Attachment`s reused, unchanged, on every
    call the run makes — which, combined with reusing the same `LlmRouter` instance throughout,
    is exactly what `llm-router` 1.0.2+ needs to deduplicate repeat file uploads by content hash
    (via each provider's own Files API) instead of re-embedding the same bytes every turn. Nothing
    extra to configure on this side to get that.
  - Known simplifications, called out rather than silently glossed over: an unregistered tool call
    ends the run via `onError` (`UnregisteredToolException`) rather than being handed back to the
    caller to resolve; growing history isn't yet run through `HistoryCompressor` before each call,
    since that needs a known target model and this loop doesn't pin one down ahead of a call.
- **History compression** (`io.github.manishpateluk.llmagentloop.compression`) — `HistoryCompressor`
  is the single entry point (`HistoryCompressor.compress(...)`, with progressively-defaulted
  overloads). It compares the request's estimated token size (padded 5% for safety) against the
  target model's context window — read from `llm-router`'s `ModelCapabilityTable`, the one
  in-memory copy of that data the whole library relies on — and, if it doesn't fit, walks an
  ordered `CompressionMethod` preference list (`STRUCTURAL_COMPACTION` →
  `SLIDING_WINDOW_TRUNCATION` → `EXTRACTIVE_SUMMARIZATION` → `LLM_SUMMARIZATION` by default)
  until the request fits or every method is exhausted, at which point it throws
  `CompressionExhaustedException` with a full per-attempt trail. All of the local methods are
  pure Java with no model to load, keeping the library's footprint suitable for modest hardware;
  `LLM_SUMMARIZATION` is the only tier that calls out, via a supplied `LlmRouter`.

## Structure

- [`java/`](java/) — Java implementation, built with Maven, depending on
  [`llm-router`](https://github.com/manishpatelUK/llm-router) from Maven Central.
