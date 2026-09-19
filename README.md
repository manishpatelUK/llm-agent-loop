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

1. **Plans, if needed.** For requests that warrant it, or specifically ask for it, the looper first asks an LLM (via
   `llm-router`) to produce a plan — a sequence of steps, where each step is its own LLM call
   with its own tools and settings. Simpler requests skip planning and the looper just figures
   out the next step on the fly, one call at a time.
2. **Executes step by step**, using `llm-router` for every LLM call. `llm-router` is configured
   with an ordered list of candidate models/providers and deterministically walks through them
   based on the capability table — the first one that can actually handle the call (given the
   tools, files, schema, etc. involved) is used.
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
6. **Follows an agent profile.** Similar in spirit to Claude "skills," each looper run can be
   given a configuration object describing the agent's overall behavior — its goals, constraints,
   and other fixed attributes — which gets serialized into the system instructions for every LLM
   call made during that run. This profile is intentionally generic so it can describe anything
   from a narrow support chatbot to a broad, autonomous cofounder-style agent.

`llm-looper` will maintains conversation state using `llm-router`'s own model types (messages, requests,
responses, etc.) rather than duplicating them. 

## Status

Early stage / under active design. This README will be kept up to date as each piece
(planning, tool registration, retry/recovery, history compression, agent profiles, etc.) is
implemented.

Implemented so far (Java):
- **Main entry point** (`io.github.manishpateluk.llmagentloop`) — `AgentLoop` is the library's
  entry point, constructed with an `LlmRouter` and run via `run(...)`, with progressively-defaulted
  overloads converging on the canonical `run(LoopRequest)`. `LoopRequest` carries the prompt, an
  optional `AgentProfile` (placeholder for the point-6 agent-behavior config, fleshed out later),
  optional `File` attachments, and the run's three async callbacks: `onResult` (the final
  `llm-router` `Response`), `onError`, and `onMessage` — status updates (`AgentMessage`: a
  `MessageType`, a message body, a timestamp, and free-form metadata) meant for things like a
  "thinking..." indicator on a frontend. Usage is always async — there is no blocking call.
  Loop execution itself (the actual planning/step/retry logic) is implemented in a later pass;
  `run(LoopRequest)` currently validates its input and throws `UnsupportedOperationException`.
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
