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
  memory. Still to do: ready-made production `VectorIndex`es (pgvector first), embedding prices in
  `llm-router` (recorded as 0 cents today), reranking, OCR so scanned PDFs and images become
  searchable, spreadsheet indexing, and searching tenant-wide and user-level knowledge together
  (see "Layered knowledge" below).
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

## Known limitations

- `document_create`'s PDFs use the standard PDF fonts, so most non-Latin scripts and emoji render as
  `?` (Word output is unaffected).

- `llm-router` drops *all* of a request's attachments when the chosen model can't take *one* of them
  (e.g. a PDF sent to a model with vision but no file input also loses the images). A `WARNING`
  status message now says when it happens, and attachments stay saved and described so tools can
  recover. `LoopRequest.requireAttachmentSupport(true)` skips such models instead, and fails if none
  remain. Dropping only the unsupported attachments belongs in `llm-router`.
- Memory embedding usage is billed to the memory's scope with no execution id, so it doesn't appear
  in a run's `result.usage()`. Workspace indexing done during a run does appear there.
- `InMemoryVectorIndex` searches by brute force, which is fine up to tens of thousands of passages.
- A tool that ignores thread interrupts can't be stopped by its timeout or by cancellation.

- `web_fetch`'s private-network check can be bypassed by DNS rebinding; deployments that need a hard
  guarantee should also block private ranges at an egress proxy or firewall.
- `McpClient` supports tools only — not MCP resources, prompts or sampling.
- `api_request` has no OpenAPI-driven generation of one typed tool per endpoint yet.
