# llm-agent-loop (Java)

The Java implementation of [`llm-agent-loop`](../README.md) — a recursive agent loop built on top of [`llm-router`](https://github.com/manishpatelUK/llm-router): give it a prompt and it plans (or doesn't), calls tools, checks for completion, and keeps going until the goal is done, reporting progress the whole way through callbacks.

## Installation

Build and install it locally:

```bash
git clone https://github.com/manishpatelUK/llm-agent-loop.git
cd llm-agent-loop/java
mvn install #then add dependency as normal into the pom.xml
```

Depend on it via `pom.xml`:

```xml
<dependency>
  <groupId>io.github.manishpateluk</groupId>
  <artifactId>llm-agent-loop</artifactId>
  <version>{version}</version>
</dependency>
```

Requires **Java 25+**.

## Configuring credentials

`AgentLoop` calls out to `llm-router` for every LLM call, so credentials work exactly as `llm-router` documents — see its [README](https://github.com/manishpatelUK/llm-router/blob/main/java/README.md#configuring-credentials) for the full environment-variable table. `AgentLoop.builder().build()` auto-detects credentials the same way `new LlmRouter()` does; pass `.adapters(...)` or `.router(...)` to the builder if you want explicit control instead.

## Quick start

```java
import io.github.manishpateluk.llmagentloop.AgentLoop;

AgentLoop loop = AgentLoop.builder().build(); // auto-detects credentials; history compression on by default

loop.run(
    "What's the capital of France?",
    result -> System.out.println(result.finalResponse().getContent()),
    error -> System.err.println("Failed: " + error.getMessage()));
```

`run(...)` is always asynchronous — it returns immediately and reports back entirely through the callbacks you supply, on a virtual thread.

## Usage examples

### Building an `AgentLoop`

`AgentLoop.builder()` is the recommended way to construct one:

```java
import io.github.manishpateluk.llmagentloop.AgentLoop;

AgentLoop loop = AgentLoop.builder()
    .adapters(myAdapters)       // or .router(myRouter) for a fully-assembled router — mutually exclusive
    .tools(myToolRegistry)      // optional, defaults to an empty registry
    .memory(myMemoryStore)      // optional, defaults to a no-op
    .compress(true)             // optional, on by default — see "History compression" below
    .build();
```

Leave both `.adapters(...)` and `.router(...)` unset to auto-detect credentials from the environment. If you already have a fully-assembled `LlmRouter` — e.g. one with its own `RequestInterceptor` for something other than compression — the original constructors remain for that case and add no factory logic on top:

```java
AgentLoop loop = new AgentLoop(myRouter);                       // or
AgentLoop loop = new AgentLoop(myRouter, myToolRegistry);        // or
AgentLoop loop = new AgentLoop(myRouter, myToolRegistry, myMemoryStore);
```

### The request: `LoopRequest`

Every `run(...)` overload funnels into the canonical one, which takes a `LoopRequest`:

```java
import io.github.manishpateluk.llmagentloop.LoopRequest;

loop.run(LoopRequest.builder()
    .prompt("Draft a launch announcement for our new pricing page.")
    .agentProfile(myAgentProfile)          // optional — see below
    .files(List.of(new File("brief.pdf"))) // optional
    .onResult(result -> { /* ... */ })
    .onError(error -> { /* ... */ })
    .onMessage(message -> { /* ... */ })   // optional, defaults to a no-op — see "Status updates" below
    .maxCostUsdCents(500)                  // optional, approximate — see below
    .maxDuration(Duration.ofMinutes(2))    // optional, approximate — see below
    .build());
```

`maxCostUsdCents` and `maxDuration` are approximate, best-effort bounds: checked after each step completes rather than mid-step, so the run may go slightly over before it notices. Crossing either stops the run **gracefully** — it still returns an answer via `onResult`, not `onError` — with `AgentLoopResult.execution().terminationReason()` telling you which bound (if either) was hit: `COMPLETED`, `COST_LIMIT_REACHED`, or `TIME_LIMIT_REACHED`.

### Shaping agent behavior: `AgentProfile`

```java
import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.PlanMode;

AgentProfile profile = AgentProfile.builder()
    .planMode(PlanMode.AUTO)
    .goals(List.of("Keep responses under 200 words", "Always cite sources"))
    .planningGuidance(List.of("Prefer 3-5 step plans over single large steps"))
    .operatingContext("You are the support agent for Acme Inc, a B2B SaaS company.")
    .maxSteps(15) // hard cap across the whole run; defaults to 25, no way to request unbounded
    .build();
```

`planMode` picks the run's shape:

| Mode | Behavior |
|---|---|
| `NEVER_PLAN` | A single one-shot LLM call — no tool loop, no planning. For requests that don't need the full machinery. |
| `ALWAYS_PLAN` | Generates an explicit `Plan` up front (via structured output), then executes its steps in order. |
| `RECURSIVE_ON_EACH_STEP` | No upfront plan — each step decides its own next action, and may delegate a self-contained sub-goal to a new thread, whose result folds back in once it completes. |
| `AUTO` (default) | A cheap structured-output call decides between `ALWAYS_PLAN` and `RECURSIVE_ON_EACH_STEP` per request. |

The whole profile is serialized to JSON and folded into the system instructions for every LLM call the run makes.

### Registering tools

```java
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import java.util.Map;

ToolRegistry tools = new ToolRegistry();
tools.register(
    ToolDefinition.builder()
        .name("get_weather")
        .description("Look up current weather for a city")
        .parameters(Map.of(
            "type", "object",
            "properties", Map.of("city", Map.of("type", "string"))))
        .build(),
    arguments -> lookUpWeather((String) arguments.get("city")));

AgentLoop loop = AgentLoop.builder().tools(tools).build();
```

A tool handler (`ToolHandler`) takes the model's parsed call arguments and returns a result string, which is fed back into the conversation. If the model calls a tool that isn't registered, the run ends via `onError` with an `UnregisteredToolException`.

### Reading the result: `AgentLoopResult`

```java
loop.run(prompt, result -> {
    System.out.println(result.finalResponse().getContent());

    for (var step : result.execution().steps()) {
        System.out.println(step.thread() + ": " + step.action() + " — " + step.description());
    }
}, error -> { /* ... */ });
```

`AgentLoopResult` carries the final `llm-router` `Response` plus a full `Execution` trace — every step taken, across every thread the run branched into (sub-tasks, or a `Plan`'s steps), keyed by a random execution id. Useful for logging, debugging, or showing the caller a full "what did the agent actually do" breakdown.

### Status updates: `onMessage`

```java
.onMessage(message -> {
    // message.type(): THINKING, PROGRESS, TOOL_CALL, TOOL_RESULT, WARNING, or INFO
    // message.executionId() / message.thread() identify which run/branch this belongs to
    updateFrontend(message.message());
})
```

Fired at every logical transition during a run — handy for a "thinking..." indicator or a live activity feed. Where it's cheap to do (already part of a structured-output call being made anyway), the message text is authored by the LLM itself rather than a static template.

### History compression

Compression is **on by default** when `AgentLoop.builder()` assembles the router for you (i.e. via `.adapters(...)` or auto-detect, not a caller-supplied `.router(...)`). It runs automatically, against the exact model each call actually targets, via `llm-router`'s `RequestInterceptor` hook — nothing further to do to get it.

```java
AgentLoop loop = AgentLoop.builder().compress(false).build(); // disable it

AgentLoop loop = AgentLoop.builder()
    .compressionMethods(List.of(CompressionMethod.STRUCTURAL_COMPACTION, CompressionMethod.SLIDING_WINDOW_TRUNCATION))
    .build(); // custom preference list instead of HistoryCompressor.DEFAULT_METHODS
```

`HistoryCompressor` (`io.github.manishpateluk.llmagentloop.compression`) can also be used standalone, or wired into your own `llm-router` setup via `HistoryCompressor.newSelfCompressingRouter(...)`, independent of `AgentLoop`.

### Long-term memory

`MemoryStore` is a placeholder for now — `MemoryStore.NONE` (the default) is a no-op. Supply your own implementation (e.g. backed by a vector store) to have it consulted for extra context at each step:

```java
MemoryStore memory = query -> myVectorStore.searchSimilar(query, 5);
AgentLoop loop = AgentLoop.builder().memory(memory).build();
```

### Error handling

Everything that stops a run short of a normal completion goes to `onError`, not thrown from `run(...)` (which itself only validates its input synchronously before dispatching the run):

- `UnregisteredToolException` — the model called a tool that isn't registered.
- `AgentLoopStepLimitExceededException` — the run exceeded `AgentProfile.maxSteps()`.
- Anything `llm-router` itself throws (e.g. `RouterExhaustedException` if every candidate provider/model fails).

Cost and time bounds (`LoopRequest.maxCostUsdCents`/`maxDuration`) are the exception — those stop the run gracefully via `onResult`, not `onError`, as described above.

## Learn more

This README only covers installing and calling the library. For the overall design — the full behavior spec is still taking shape — see the [project README](../README.md) at the repo root, which is kept up to date as each piece is implemented.
