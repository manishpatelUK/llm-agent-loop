# Roadmap

Planned work that isn't implemented yet. Each item says what's missing and the direction agreed so
far. Remove an item once it ships (and record it in the README's Status section).

## Producing and doing more

- **Email and calendar providers.** The `EmailService`/`CalendarService` interfaces and their tools
  exist; ready-made implementations (Gmail, Microsoft Graph, SMTP/IMAP, CalDAV) could ship as
  optional modules.
- **Richer documents.** Images in Word/PDF/PowerPoint output, embedded fonts for non-Latin scripts in
  PDFs, real Word list numbering and heading styles, 16:9 slide layouts, and more chart types.
- **Semantic search over the user's own material.** Memory search is keyword-only and
  `workspace_search` is substring matching. Add an embeddings interface and use it for memory recall
  and for "find what we agreed with Acme" across workspace documents.
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

- **Structured final answers.** An optional response schema for the final answer, so a UI can
  render cards/buttons rather than only text.
- **Usage metering.** Cost is tracked per run; aggregate it per tenant/user for billing and quotas.
- **Testing kit.** Publish a fake `ProviderAdapter` and scripted-response helpers (like the ones in
  this repo's tests) so implementors can test their agents without calling real models.
- **Parallel execution.** Tool calls in one response, a plan's `parallelGroup` steps and sub-tasks
  all run sequentially today.
- **"Thread" terminology.** Plan steps all record the same thread number, while sub-tasks get new
  ones; the term is doing double duty (reasoning branch vs. plan step). Settle it before parallel
  execution makes the trace ambiguous.
- **Prompt-injection hardening.** Content from web pages, MCP servers and uploaded documents is only
  *described* as untrusted (in skill guidance). Mark tool results as data in the prompt structure and
  optionally screen them.
- **Conversation store maintenance.** Stored chat history grows without bound (requests are still
  compressed to fit each model); add summarization or truncation at the store level.

## Known limitations

- `document_create`'s PDFs use the standard PDF fonts, so most non-Latin scripts and emoji render as
  `?` (Word output is unaffected).
- Answer streaming is token-by-token only for providers with native streaming in `llm-router`
  (Anthropic, OpenAI); others deliver each step's text in one piece.

- `llm-router` drops *all* of a request's attachments when the chosen model can't take *one* of them
  (e.g. a PDF sent to a model with vision but no file input also loses the images). Attachments are
  still saved to the workspace and described to the model, so tools can recover; finer-grained
  dropping belongs in `llm-router`.
- A tool that ignores thread interrupts can't be stopped by its timeout or by cancellation.

- `web_fetch`'s private-network check can be bypassed by DNS rebinding; deployments that need a hard
  guarantee should also block private ranges at an egress proxy or firewall.
- `McpClient` supports tools only — not MCP resources, prompts or sampling.
- `api_request` has no OpenAPI-driven generation of one typed tool per endpoint yet.
