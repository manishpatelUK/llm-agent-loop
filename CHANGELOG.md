# Changelog

Changes that affect callers of `llm-agent-loop`, newest first: what changed, and what calling code
needs to do, if anything.

## Java 1.0.11 — on `llm-router` 1.0.14

### Agents that learn unfamiliar REST APIs

An agent can now do a task against an API nobody wrote an integration for, and save what worked as
a procedure that it, or your code, runs again later.

- **Connections per run: `ConnectionSource`.** `ApiTools.request(ConnectionSource)` looks a run's
  connections up from its `Scope` on every call, so each tenant can have its own, added at runtime.
  The tool definitions then name no connections (the same for every tenant, and cacheable), and the
  model lists the run's connections with the new **`api_connections`** tool.
  `ApiTools.all(source)` gives `api_connections`, `api_request` and `api_describe`.
  `ApiTools.request(List<ApiConnection>)` works as before.
- **Form and text bodies.** `api_request` has two new optional arguments: `form` (name/value pairs,
  sent as `application/x-www-form-urlencoded`) and `content_type` (`application/json`, the form type,
  or `text/plain`). `ApiConnection.Builder.bodyType(ApiBodyType.FORM)` sets a connection's default,
  as Stripe needs. JSON bodies must still parse. `Content-Type` now carries `charset=utf-8`.
- **OAuth2.** `OAuth2.clientCredentials(...)` and `OAuth2.refreshToken(...)` are `ApiAuth`s that:
  - cache access tokens per scope (the tenant by default);
  - refresh them shortly before they expire;
  - store rotated refresh tokens through your `OAuth2.RefreshTokens`;
  - keep secrets out of exception messages.

  New `ApiAuth.renewAfterUnauthorized(scope)` (default `false`) lets any auth renew and have the
  request retried once after a 401; `OAuth2` does.
- **`api_describe`.** A compact, searchable list of a connection's endpoints, read from the OpenAPI 3
  or Swagger 2 (JSON) description set with `ApiConnection.Builder.openApiSpec(url)`.
- **Procedures.** `Procedure` is a named, versioned recipe against one connection:
  - typed parameters;
  - steps whose path, query, body and form are `{{param}}` / `{{step.value}}` templates;
  - JSON Pointer `keep`s for the values later steps need;
  - an optional output template;
  - `writes()`.

  Around it:
  - `ProcedureStore` (with `InMemoryProcedureStore`) keeps every version.
  - `ProcedureRunner` runs a procedure in code, under the same connection, path and auth rules as
    `api_request`.
  - `ProcedureTools.all(source, store)` gives the model `procedure_list`, `procedure_get`,
    `procedure_run` and `procedure_save`.
  - `Skills.apiProcedures(source, store)` bundles them with the API tools, `web_fetch`, and guidance
    on how to learn an API, explore it safely, save procedures and repair them.
- **Which calls write.**
  - New `RegisteredTool.withWriteCheck(...)` lets a tool declare which of its calls change something
    in another system.
  - New `ToolContext.writes(call)` asks the tool.
  - `api_request` declares its non-GET/HEAD calls, `procedure_run` declares procedures that write,
    and `procedure_save` declares saving one.
  - `UntrustedContentGuard` now also holds back every call that writes, as well as the tools it named
    before.

### Upgrading from 1.0.10

No code changes are needed. To do:

- **`RegisteredTool` has a new last component, `writeCheck`.** The old constructors remain. Code
  that calls the 6-argument canonical constructor still compiles (it's kept as an overload). Code
  that deconstructs the record with a record pattern needs the extra component.
- **Approval hooks** can use `context.writes(call)` instead of matching tool names and methods,
  which also covers `procedure_run`.
- **A custom `ToolContext.RunAccess`** (rare; tests) can implement the new `tool(name)` so that
  `writes(call)` works with it. Without it, nothing counts as a write.

## Java 1.0.10 (unreleased) — on `llm-router` 1.0.14

### Prompt caching that works for agent loops, on every provider

Runs used to send each step's goal again at the end of the request (a large task, such as a deck in
the prompt, every step), put recalled memory, limit warnings and the attachment note into the system
prompt, and put a new random id into the system prompt on every run. So the conversation was almost
never read back from providers' prompt caches: on Anthropic, it was written again at 1.25× the input
price every step. Now:

- **Each working call extends the previous one.** Same tools (now always in name order), same system
  instructions, same conversation with new turns at the end. A goal is the first call's prompt and
  then a message in the conversation, once; later prompts are `AgentLoop.CONTINUE_PROMPT`. Plan steps
  add "Plan step i of n: ..." messages; a sub-task's goal is its first prompt, as before.
- **Recalled memory and limit warnings go in the prompt**, never in the system instructions or history.
- **The attachment note goes with the goal** (in its first prompt, then its message), and attached
  images and PDFs ride on the goal's message instead of every prompt.
- **Files a tool shows the model** (`ToolContext.showToModel`) join the conversation in a message after
  that step's tool results, and stay visible; `MAX_SHOWN_FILES` (10) is now a per-step limit rather
  than a sliding window over the run.
- **`operatingContext` is a system message of its own**, after the instructions, so the instructions
  stay identical from turn to turn. The model sees the same text as before.
- **The untrusted-content marker id is stable per conversation**: an HMAC of the scope under a key, so
  every run in a conversation sends the same system instructions. New
  `AgentLoop.Builder.untrustedMarkerSecret(secret)` (16+ characters) shares the key across instances
  and restarts; without it each loop has a random key.
- **History compression is stable**: a self-compressing router reuses its earlier compression of a
  history's start and appends to it, compressing again only when that no longer fits; LLM summaries
  are made once rather than every call. New `HistoryCompressor.interceptor(self, methods)`. Compression
  also keeps attachments on the messages it rewrites, which it used to drop.

Measured on real providers (cheapest models, same tasks, 1.0.9 + router 1.0.13 vs 1.0.10 + router
1.0.14): a 15-tool-call research run on Claude Haiku 5.5 went from 4% to 88% of input read from the
cache and cost 85% less; a 5-call run 61% less; a second chat turn 45% less; four chat turns on OpenAI
`gpt-6-luna` 59% less. Tool runs on GPT-6 failed under 1.0.9 (see the upgrade notes); with router
1.0.14's cache breakpoint, a 4-step tool run on `gpt-6-luna` reads the whole conversation back each
step and cost 34% less than the same run without it. The saving grows with the number of steps.

### Usage

- **`UsageRecord` carries cached and reasoning tokens**: `cacheReadTokens`, `cacheWriteTokens`,
  `cacheWriteLongTtlTokens` (parts of `inputTokens`) and `reasoningTokens` (part of `outputTokens`),
  plus `uncachedInputTokens()`. They're new components at the end of the record; the old constructors
  remain and set them to 0. `UsageRecord.of(executionId, scope, purpose, response, tags)` builds one
  from an `llm-router` `Response`.
- **`UsageTotals` sums them too** (new components at the end; the old 5-argument constructor remains),
  with `uncachedInputTokens()` and `cacheReadFraction()`.
- **A run's `usage()` now includes its delegated runs' calls** (agents started from its tools). Their
  records are still metered under their own execution id, as before. If you add a delegate's
  `AgentLoopResult.usage()` to its parent's yourself, stop: it's counted already.
- **Embedding records carry the run's `LoopRequest.tags`** (they had none). New
  `SemanticSearch.RunContext` component `tags`; the old constructor remains.
- **Costs follow `llm-router` 1.0.14's cache prices**, which are now set for every model: cached input
  on Claude Opus 5.5 and Sonnet 5.5 is priced at 0.05× (it was estimated at 0.1×), and OpenAI,
  Perplexity and OpenRouter cached input at their discounts (it was the full input price).

### Upgrading from 1.0.9

No code changes are needed to build and run. To do:

- **Store the new usage fields.** A `UsageMeter` that writes records to a database should add
  `cacheReadTokens`, `cacheWriteTokens` (and optionally `cacheWriteLongTtlTokens`, `reasoningTokens`)
  as columns. They're what shows the cache working: from a conversation's second call, reads should be
  most of `inputTokens`.
- **Don't double-count delegates** (see above).
- **Set `untrustedMarkerSecret`** to the same secret (from configuration, 16+ characters) on every
  instance when several instances serve the same conversations, or when you restart often.
- **Tests that read the goal from `request.getPrompt()`** on later calls of a run (a custom adapter or
  a `MockModel` responder): the goal is the prompt only on a goal's first call. Use
  `MockModel.goal(request)`, or the conversation. Tests that look for recalled memory, limit warnings,
  the attachment note or the operating context as `SYSTEM` history messages, or for images and PDFs on
  `Request.attachments` after the first call, should look in the prompt / `everyAttachment()` instead.
- **`showToModel`** files no longer drop off after 10 across a run; each stays in the conversation.
- **OpenAI GPT-6 models can now run agent steps.** llm-router 1.0.14 calls OpenAI's Responses API,
  so a step with tools routed to a GPT-6 model succeeds instead of failing with a 400. Code that reads `Response.getOriginal()` from OpenAI gets a
  `com.openai.models.responses.Response` rather than a `ChatCompletion`.
