# llm-agent-loop

A library that provides the recursive "agent loop" needed to turn a single request into a
completed task by repeatedly calling an LLM, using tools, and adapting to failures along the
way. It sits on top of [`llm-router`](https://github.com/manishpatelUK/llm-router) for the
actual provider/model calls, and is meant to be the reusable core underneath many different
kinds of agents — from a chatbot backend to a fully autonomous business assistant that handles
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
   registered tool can be a simple functional callback with typed parameters), and a broad set
   comes built in — memory, files, exact calculation and data queries, web fetch/search, generic
   authenticated API calls, Excel spreadsheets, asking the user, delegating to other agents, and
   any MCP server's tools. If the LLM asks to call a tool that isn't registered, that request is
   handed back to the caller to handle rather than failing silently.
5. **Context window aware.** When a conversation risks breaching the model's context window, the
   looper compresses history through an ordered list of named strategies — cheap, local, and
   algorithmic first (deduplication/whitespace cleanup, oldest-turn truncation, a hand-rolled
   TextRank extractive summarizer with no model to load), escalating to an LLM summarization call
   via `llm-router` only if those aren't enough. Each strategy is tried in order, like
   `llm-router`'s own provider fallback, until the request fits or every strategy is exhausted.
   This runs automatically, against the exact model each call actually targets, via an
   `llm-router` 1.0.3+ hook — see `HistoryCompressor.newSelfCompressingRouter` below.
6. **Follows an agent profile.** Similar in spirit to Claude "skills," each looper run is given an
   `AgentProfile` describing the agent's overall behavior — its plan mode, standing goals,
   planning guidance, operating context, and a hard step-count cap — which gets serialized into
   the system instructions for every LLM call made during that run. This profile is intentionally
   generic so it can describe anything from a narrow support chatbot to a broad, autonomous
   general-purpose business agent.
7. **Runs reusable agents.** An agent can be defined as Markdown plus skills and tools, then run
   for any number of users: each run's scope selects that user's memory and files, so one
   definition safely serves a whole multi-user product.

The looper maintains conversation state using `llm-router`'s own model types (messages, requests,
responses, etc.) rather than duplicating them.

## Status

Early stage / under active design. This README will be kept up to date as each piece
(planning, tool registration, retry/recovery, history compression, agent profiles, etc.) is
implemented.

Implemented so far (Java):
- **Main entry point** (`io.github.manishpateluk.llmagentloop`) — `AgentLoop` is the library's
  entry point, run via `run(...)`, with progressively-defaulted overloads converging on the
  canonical `run(LoopRequest)`.
  - `AgentLoop.builder()` is the recommended way to construct one: `.adapters(...)` or `.router(...)`
    (mutually exclusive; neither means auto-detect credentials from the environment), optional
    `.tools(...)`/`.memory(...)`, and history compression **on by default** — `.compress(false)` to
    disable it, or `.compressionMethods(...)` for a custom `CompressionMethod` preference list
    instead of `HistoryCompressor.DEFAULT_METHODS`. When the builder assembles the router itself
    (i.e. via `.adapters(...)`/auto-detect, not a caller-supplied `.router(...)`), compression is
    wired in via `HistoryCompressor.newSelfCompressingRouter` — see below. The original constructors
    (`new AgentLoop(router[, tools[, memory]])`) remain for callers that already have a
    fully-assembled `LlmRouter` (e.g. one with its own `RequestInterceptor` for something other
    than compression) and want no factory logic in the way; they do nothing with compression
    automatically.
  - `LoopRequest` carries the prompt, an optional `AgentProfile`, optional attachments
    (`InputFile`s, from bytes or streams), optional chat history and `RouterConfig`, and the run's
    three async callbacks: `onResult` (an `AgentLoopResult` — the final `llm-router`
    `Response` plus the full `Execution` trace), `onError`, and `onMessage` — status updates
    (`AgentMessage`: an execution id, a thread number, a `MessageType`, a message body, a
    timestamp, and free-form metadata) meant for things like a "thinking..." indicator on a
    frontend. `run` returns immediately with a `RunHandle` (to cancel the run) and the work happens
    on a virtual thread, reporting back through those callbacks; `runAndWait` blocks instead.
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
    against runaway recursion (`AgentLoopStepLimitExceededException`). Every LLM call the run
    makes counts as a step — including `AUTO`'s plan-needed check and plan generation.
  - A `Plan`'s steps share one history, seeded with the overall goal and accumulating each
    completed step's answer, so later steps build on earlier ones. A sub-task's result is folded
    back as a tool result correlated (by call id) to the `spawn_sub_task` call that requested it.
  - When a response pairs a control tool (`report_complete`/`spawn_sub_task`) with other tool
    calls, only the control tool is acted on — the others are reported via a `WARNING`
    `AgentMessage`, never dropped silently.
  - `LoopRequest.maxCostUsdCents` and `maxDuration` are optional, approximate per-run bounds —
    unlike `maxSteps`, they default to unbounded (current no-bound behavior) and, when set, stop
    the run *gracefully* rather than failing it: checked after each step completes (not mid-step,
    so the bound is met "at or after", never exact), the run returns whatever answer it has so far
    via `onResult` — not `onError` — with a `WARNING` `AgentMessage` explaining why, and
    `Execution.terminationReason()` (`COMPLETED` / `COST_LIMIT_REACHED` / `TIME_LIMIT_REACHED`) on
    the result records what happened. The check applies globally across the whole run — a bound
    crossed inside a sub-task or a plan step stops everything, not just that branch.
  - Attachments are prepared once per run and the same `Attachment`s reused, unchanged, on every
    call the run makes — which, combined with reusing the same `LlmRouter` instance throughout,
    is exactly what `llm-router` 1.0.2+ needs to deduplicate repeat file uploads by content hash
    (via each provider's own Files API) instead of re-embedding the same bytes every turn. Nothing
    extra to configure on this side to get that.
  - When the router compresses history, each compression (or failure to fit) is reported on the
    run's `onMessage` and recorded in its `Execution` as a `HISTORY_COMPRESSION` step, attributed
    to the thread whose call triggered it — via `HistoryCompressor.withListener` (see below).
  - A tool call the model makes that isn't registered is handed back to the caller via
    `LoopRequest.onUnregisteredTool` (an `UnregisteredToolHandler`), which runs on the run's
    virtual thread and so may block while it resolves the call (e.g. asking a human). A returned
    result is fed back as that call's tool result and the run continues; `Optional.empty()` — and
    the default, `UnregisteredToolHandler.NONE` — ends the run via `onError`
    (`UnregisteredToolException`).
  - Every call that offers tools sets `llm-router` 1.0.5's `requiredFeatures(TOOLS)`, so it only
    routes to models that can call tools, rather than having them (and the loop's own
    `report_complete`) silently stripped.
- **Chat-product essentials.**
  - **Conversation history.** Through a `ConversationStore` keyed by the session's scope, or passed explicitly per request. The store is your own implementation or three database callbacks (`ConversationStore.of`). Long sessions are compacted: older turns are summarized by default (metered), and a custom `ConversationCompactor` or `OFF` can replace that.
  - **Attachments in any format.** Images and PDFs go to the model directly, small text files inline. All are saved under `uploads/` in the workspace by default, with an opt-out, so tools can work on them.
  - **Cancellation.** `RunHandle.cancel()` interrupts blocking work and finishes the run with `CANCELLED`.
  - **Model choice.** A `RouterConfig` per agent, in front matter or code, or per request.
  - **`ToolInterceptor`.** Before/after hooks on every tool call, for approvals, audit logs, redaction or rate limits.
  - **Per-tool timeouts.** Overrunning tools are interrupted, and the model is told.
  - **Structured answers.** `LoopRequest.answerSchema` returns the final answer as data matching a JSON schema, as well as text.
  - **Usage metering.** Every model call is metered (`UsageRecord`: scope, purpose, model, tokens, cost), on by default with an `InMemoryUsageMeter` holding per-tenant and per-user totals. Pass your own `UsageMeter`, or `UsageMeter.NONE` to switch it off. `AgentLoopResult.usage()` has each run's totals.
  - **Dropped attachments are flagged.** A `WARNING` is sent when the routed model couldn't take the attachments.
  - **Answer streaming.** `LoopRequest.answerStream` receives the final answer as it's written, via `llm-router`'s `completeStreaming`. Final answers are plain text, and a step whose streamed text turns out to precede a tool call is discarded.
- **Reusable agents: scope, memory, workspace, and tool context** — one `AgentLoop` can serve
  every user of a multi-user product. Each run carries a `Scope` (opaque tenant/user/session ids
  from the implementor; a private throwaway scope when omitted), and long-term memory
  (`MemoryStore`) and file storage (`Workspace`) are partitioned by it at a configurable
  `ScopeLevel` — `USER` by default, so a user's sessions share data and users never do. Both are
  interfaces for implementors to back with their own storage, with heap-only `InMemoryMemoryStore`
  and `InMemoryWorkspace` provided (no local-disk workspace, deliberately). Tools receive a
  `ToolContext` whose memory and workspace are already bound to the run's scope, so a model can't
  reach another user's data; `ScopedWorkspace` enforces path safety (no `..`, absolute paths or
  drive letters) and `WorkspaceLimits` in front of any backend. A handler can throw
  `ToolInputException` to hand a fixable problem back to the model as an `"Error: ..."` result
  instead of ending the run. `AgentLoopResult.changedFiles()` lists the workspace files a run
  produced. `Scope.key()` gives implementors a stable, collision-safe storage key (e.g.
  `acme/alice/*`) for their own stores. The
  [Java README](java/README.md#extending-the-library-your-own-memory-workspace-and-tools) has a
  full guide to implementing your own `MemoryStore`, `Workspace` and tools.
- **Agents defined in Markdown** (`io.github.manishpateluk.llmagentloop.agent`) — an `Agent` is
  a reusable definition: Markdown instructions (with optional front matter for name, description,
  plan mode and step cap), `Skill`s, its own tools, and other agents it may delegate to. It holds
  no user data or infrastructure, so one definition serves every user. An `AgentRuntime` holds the
  shared router, memory, workspace and base tools and runs any agent for any `Scope`
  (`run`/`runAndWait`), building each agent's tool set once; agents delegating to each other run on
  the same runtime with the same scope. Session management stays with the implementor.
- **Skills** (`io.github.manishpateluk.llmagentloop.skill`) — a `Skill` is tools plus Markdown
  guidance on using them well, folded into the agent's instructions. Ready-made: `Skills.memory()`,
  `files()`, `spreadsheets()`, `dataAnalysis()`, `web(...)`, `askingTheUser(...)`.
- **Built-in tools**, each reporting fixable problems back to the model rather than ending the run:
  - `MemoryTools` (`memory_save`/`search`/`forget`) and `WorkspaceTools`
    (`workspace_list`/`read`/`write`/`edit`/`delete`/`search`, plus `workspace_view` to show an
    image or PDF to the model); `DocumentTools` — `document_read` (text of PDF, Word and PowerPoint
    files, via PDFBox and POI);
  - `UtilityTools` — `current_datetime`, `date_calculate` (incl. business days) and `calculate`
    (exact decimal arithmetic, so money comes out to the penny); `DataTools` — `data_query`
    (filter/group/aggregate CSV or JSON in the workspace, with lenient number parsing);
  - `HumanTools.askHuman` — `ask_human`, blocking on an implementor handler until the user answers;
    `DelegationTools` — `delegate_to_agent`, chains capped at depth 3;
  - `WebTools` — `web_fetch` (public pages as readable text via jsoup, or saved to the workspace;
    SSRF-guarded: private/loopback/metadata addresses refused, every redirect hop re-checked) and
    `web_search` over a `SearchProvider` (`BraveSearch`, `TavilySearch`);
  - `ApiTools` — `api_request` over implementor-registered `ApiConnection`s: credentials resolved
    per scope and never shown to the model, paths confined to the base URL, read-only unless a
    connection allows writes, optional path allow-lists, JSON Pointer `select`;
  - `SpreadsheetTools` (Apache POI) — `spreadsheet_create`/`read`/`update`, with every formula
    evaluated before saving so errors reach the model, not the user, and column/bar/line/pie charts;
  - `DocumentTools.create` and `PresentationTools` — `document_create` (Markdown to Word or PDF)
    and `presentation_create` (PowerPoint decks with speaker notes);
  - `EmailTools` and `CalendarTools` — `email_send`/`draft`/`search`/`read` and
    `calendar_list_events`/`create_event`/`find_free_time` over implementor-supplied
    `EmailService`/`CalendarService` interfaces, with a ready-made `CalDavCalendar` for any CalDAV
    server;
  - `McpClient` — any MCP server's tools (stdio or Streamable HTTP), so tools can be written in any
    language.
  `ToolSchemas`/`ToolArguments` help implementors write their own tools the same way.
- **History compression** (`io.github.manishpateluk.llmagentloop.compression`) — `HistoryCompressor`
  is the single entry point (`HistoryCompressor.compress(...)`, with progressively-defaulted
  overloads). It compares the request's estimated token size (padded 5% for safety) against the
  target model's context window — read from `llm-router`'s `ModelCapabilityTable`, the one
  in-memory copy of that data the whole library relies on — and, if it doesn't fit, walks an
  ordered `CompressionMethod` preference list (`STRUCTURAL_COMPACTION` →
  `EXTRACTIVE_SUMMARIZATION` → `LLM_SUMMARIZATION` → `SLIDING_WINDOW_TRUNCATION` by default)
  until the request fits or every method is exhausted, at which point it throws
  `CompressionExhaustedException` with a full per-attempt trail. All of the local methods are
  pure Java with no model to load, keeping the library's footprint suitable for modest hardware;
  `LLM_SUMMARIZATION` is the only tier that calls out, via a supplied `LlmRouter`.
  - `HistoryCompressor.newSelfCompressingRouter(adapters[, methods])` builds an `LlmRouter` that
    runs this compression automatically on every call it serves, against the exact model each
    attempt actually targets — via `llm-router` 1.0.3's `RequestInterceptor` hook, a last-chance
    callback to modify a request immediately before it's sent, run once per candidate in
    `llm-router`'s own fallback loop (so a fallback to a different model recompresses correctly
    against *that* model's context window too). Use it in place of `new LlmRouter(adapters)`
    anywhere a router is constructed — including for `AgentLoop`, which needs no code changes to
    benefit, since it already just uses whatever `LlmRouter` it's given. If every method is
    exhausted for a candidate, the failure propagates out of the hook rather than being swallowed —
    `llm-router` treats that like any other in-attempt failure and falls back to the next
    candidate, which may have a larger context window and need no compression at all.

See [ROADMAP.md](ROADMAP.md) for what's planned next.

## Structure

- [`java/`](java/) — Java implementation (Java 25+), built with Maven, depending on
  [`llm-router`](https://github.com/manishpatelUK/llm-router) 1.0.5+ from Maven Central, Jackson 3
  (`tools.jackson.*`) for its own JSON handling, jsoup (HTML to text for `web_fetch`), Apache
  POI (spreadsheets, Word/PowerPoint reading) and PDFBox (PDF reading), with their logging bridged
  to SLF4J. All permissively licensed (MIT/Apache 2.0).
