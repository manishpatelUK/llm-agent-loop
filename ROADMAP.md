# Roadmap

Planned work that isn't implemented yet. Each item says what's missing and the direction agreed so
far. Remove an item once it ships (and record it in the README's Status section).

## Producing and doing more

- **Email and calendar providers.** `CalDavCalendar` ships. Still to do: SMTP/IMAP email (planned on
  Jakarta Mail; deferred for now), then Gmail and Microsoft Graph for email and calendar, and CalDAV
  free/busy for other attendees (RFC 6638 scheduling).
- **Richer documents.** Images in Word/PDF/PowerPoint output, embedded fonts for non-Latin scripts in
  PDFs, real Word list numbering and heading styles, 16:9 slide layouts, and more chart types.
- **More from semantic search.** Shipped: embeddings, indexing modes, `knowledge_search`, hybrid
  memory. Still to do: ready-made production `VectorIndex`es (pgvector first), reranking, OCR so scanned PDFs and images become
  searchable, spreadsheet indexing, and searching tenant-wide and user-level knowledge together
  (see "Layered knowledge" below).
- **Local embeddings.** Every step that recalls memory, and every document indexed, calls a hosted
  embeddings API (OpenAI's `text-embedding-3-small` by default), paying for each call and its round
  trip. Direction: an optional module (its own artifact, so the core doesn't pull in a model runtime)
  with an `Embedder` that runs a small sentence-transformers model in-process through ONNX Runtime and
  a Hugging Face tokenizer, metered as tokens at no cost. Switching models means re-indexing, since
  `Embeddings.model()` keeps vectors from different models apart. The same runtime could later serve
  a local reranker or a ready-made `ContentScreener` classifier.
- **Background, scheduled and resumable work.** Runs live on in-process virtual threads: nothing
  survives a restart, nothing runs on a schedule, and a run waiting in `ask_human` is lost on
  redeploy. Needs a persistent task queue, a scheduler interface, and checkpoints so a waiting run
  can resume.
- **Layered knowledge across scope levels.** Memory and workspace each live at one `ScopeLevel`.
  Teams need tenant-wide shared knowledge *and* private per-user memory: reads should be able to
  draw on both levels while writes stay at the user's.
- **Sandboxed code execution.** For data analysis and charts beyond `data_query`/spreadsheets. Best
  delivered through provider-run code execution (needs pass-through support in `llm-router`) or an
  MCP sandbox server, rather than executing code in this process.

## Product polish and operations

- **Parallel execution.** Tool calls in one response, a plan's `parallelGroup` steps and sub-tasks
  all run sequentially today.
- **"Thread" terminology.** Plan steps all record the same thread number, while sub-tasks get new
  ones; the term is doing double duty (reasoning branch vs. plan step). Settle it before parallel
  execution makes the trace ambiguous.
- **Prompt-injection hardening, next steps.** Labelling, taint tracking, `UntrustedContentGuard` and
  `ContentScreener` ship. Still to do: a ready-made screener (e.g. a small classifier model through
  `llm-router`), and per-tool trust levels for implementor-owned APIs that don't need labelling.

## Long runs and resilience

Found running long background reports (a 10,000-token report in one answer) in a production app.

- **Stream long calls.** The loop's model calls aren't streamed unless an `AnswerStream` is set; a
  single 10,000-token answer took two minutes as one blocking call. Stream internally when a call's
  output cap is high, so long answers are robust to slow providers and time limits can act sooner.
- **Plans for long tasks.** The planner's output is capped (`HousekeepingLimits.planTokens`, 8,000);
  for a long task a plan can be cut off mid-JSON and is then dropped ("Could not parse structured
  output"). Cap the number and length of steps in the planning prompt, or raise the budget when
  truncation is detected, rather than losing the plan.
- **Provider retries and errors as messages.** A provider client retrying (the Anthropic Java client
  sleeps between attempts) or failing is invisible to the caller until the run ends. Surface each
  retry and error from `llm-router` through `onMessage` as a `WARNING`, so a run whose calls keep
  failing shows why while it happens.
- **A heartbeat.** A periodic "still working" callback from a run, so a job queue can tell a slow
  report from a dead worker rather than relying on a fixed time limit alone.
- **Answers written in sections.** A request option to write a long answer section by section, each
  call seeing the sections already written, then joined: for reports too long for one answer. Built
  in an app first (arthur-os-backend's `RunAgentHandler`, for agents with staged reports); generalise
  it here.

## Known limitations

- On-demand skills: `load_skill` remembers per run which skills were loaded, and loading one again
  returns a short reminder. If history compression has since dropped the original `load_skill`
  result, the model no longer has those instructions. Fix: when compression removes a loaded
  skill's result, mark that skill as not loaded again.
- `delegate_to_agent` lists every delegate in its tool description, one line each (capped at 160
  characters). That's about 2–3k characters with 40 delegates. Provider limits on tool-description
  length haven't been checked; if one applies, the list may need to move to the system prompt or
  the `agent` parameter's description.

- `document_create`'s PDFs use the standard PDF fonts, so most non-Latin scripts and emoji render as
  `?` (Word output is unaffected).

- `llm-router` drops *all* of a request's attachments when the chosen model can't take *one* of them
  (e.g. a PDF sent to a model with vision but no file input also loses the images). A `WARNING`
  status message now says when it happens, and attachments stay saved and described so tools can
  recover. `LoopRequest.requireAttachmentSupport(true)` skips such models instead, and fails if none
  remain. Dropping only the unsupported attachments belongs in `llm-router`.
- `ON_WRITE_BACKGROUND` indexing is metered without an execution id and isn't counted in the run's
  `result.usage()`, since it may finish after the run does.
- `InMemoryVectorIndex` searches by brute force, which is fine up to tens of thousands of passages.
- A tool that ignores thread interrupts can't be stopped by its timeout or by cancellation.

- `web_fetch`'s private-network check can be bypassed by DNS rebinding; deployments that need a hard
  guarantee should also block private ranges at an egress proxy or firewall.
- `McpClient` supports tools only — not MCP resources, prompts or sampling.
- `api_request` has no OpenAPI-driven generation of one typed tool per endpoint yet.
