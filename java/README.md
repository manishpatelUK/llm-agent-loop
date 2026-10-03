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
    .memory(myMemoryStore)      // optional, defaults to a no-op — see "Memory, workspace and scope" below
    .workspace(myWorkspace)     // optional, defaults to a no-op — ditto
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
    .scope(Scope.of(tenantId, userId, sessionId)) // optional — whose memory/files; see "Memory, workspace and scope"
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
    .maxSteps(15) // hard cap on LLM calls across the whole run (planning included); defaults to 25, no way to request unbounded
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

A tool handler takes the model's parsed call arguments and returns a result string, which is fed back into the conversation. Handlers that need to know *whose* run is calling them — e.g. to look up the current tenant's API key — take a `ToolContext` too:

```java
tools.register(crmLookupDefinition, (arguments, context) ->
    crmFor(context.scope().tenantId()).find((String) arguments.get("name")));
```

`ToolContext` carries the run's `Scope`, execution id, thread, and its `memory()`/`workspace()` — already bound to that scope, so a tool can't reach another user's data whatever the model asks for.

Throw `ToolInputException` for a problem the model can fix (a bad argument, a missing record): its message goes back to the model as `"Error: ..."` and the run carries on, so the model can try again. Any other exception ends the run via `onError`.

If the model calls a tool that isn't registered, it's handed to `LoopRequest.onUnregisteredTool` to resolve:

```java
loop.run(LoopRequest.builder()
    .prompt("...")
    .onUnregisteredTool(call -> call.getName().equals("ask_human")
            ? Optional.of(askHuman((String) call.getArguments().get("question"))) // may block — runs on the run's virtual thread
            : Optional.empty())
    // ...
    .build());
```

A present result is fed back to the model as that call's tool result and the run carries on; `Optional.empty()` ends the run via `onError` with an `UnregisteredToolException`. The default (`UnregisteredToolHandler.NONE`) resolves nothing.

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

`result.changedFiles()` lists the workspace paths the run created, changed or deleted — the documents to hand back to your user.

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

Each compression shows up as an `INFO` status update (or a `WARNING` if the request couldn't be made to fit and the router is falling back to its next candidate) and as a `HISTORY_COMPRESSION` step in the `Execution` trace. That works with a caller-supplied `.router(...)` too, as long as it compresses via `HistoryCompressor`.

`HistoryCompressor` (`io.github.manishpateluk.llmagentloop.compression`) can also be used standalone, or wired into your own `llm-router` setup via `HistoryCompressor.newSelfCompressingRouter(...)`, independent of `AgentLoop`. To hear about what it did from your own code, wrap the call in `HistoryCompressor.withListener(listener, () -> router.complete(request))` — the listener is scoped to the calling thread, so it covers `complete(...)` but not `completeAsync(...)`.

### Memory, workspace and scope

One `AgentLoop` — one agent — can serve every user of your product. What keeps their data apart is the `Scope` you pass on each run: opaque tenant, user and session ids from your own system.

```java
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.tool.builtin.MemoryTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;

AgentLoop loop = AgentLoop.builder()
    .tools(new ToolRegistry()
        .registerAll(MemoryTools.all())       // memory_save, memory_search, memory_forget
        .registerAll(WorkspaceTools.all()))   // workspace_list/read/write/edit/delete/search
    .memory(new InMemoryMemoryStore())        // or your own MemoryStore
    .workspace(new InMemoryWorkspace())       // or your own Workspace
    .build();

loop.run(LoopRequest.builder()
    .prompt(userMessage)
    .scope(Scope.of(tenantId, userId, sessionId))  // or Scope.forUser(userId, sessionId) without tenants
    // ...
    .build());
```

- **Memory** is long-term facts. The model manages it explicitly through the `memory_*` tools, and the loop also searches it with each step's goal and adds the best matches to that step's context.
- **Workspace** is files — drafts, reports, spreadsheets, text or binary. `result.changedFiles()` tells you what a run produced.
- **Sharing level.** Both are shared at `ScopeLevel.USER` by default: across all of one user's sessions, never between users. Change that with `.memoryLevel(...)`/`.workspaceLevel(...)` — `SESSION` for per-conversation data, or `TENANT` to share across a tenant's users (deliberately: anything one user's agent saves, every other user's agent can then read).
- **No scope** gives a run a private, throwaway scope: nothing leaks between callers who forget to pass one, but nothing persists between runs either.

**Bringing your own storage.** See [Extending the library](#extending-the-library-your-own-memory-workspace-and-tools) below for full examples (Postgres memory, S3 workspace, custom tools). In short: `InMemoryMemoryStore` and `InMemoryWorkspace` live in the heap and are lost on restart — fine for development and tests, not for production. For real persistence, implement `MemoryStore` (`save`/`search`/`delete`, e.g. over a vector store) or `Workspace` (`read`/`write`/`delete`/`list`, e.g. over S3). Both receive a `Scope` already reduced to the configured level, so your implementation just stores and looks up data under the key it's given. Workspace safety lives in front of your implementation, in `ScopedWorkspace`: paths arrive normalized and relative (no `..`, no absolute paths or drive letters), and `WorkspaceLimits` (default 1,000 files, 10 MB per file, 100 MB in total per scope; change with `.workspaceLimits(...)`) are already enforced. There's deliberately no local-disk workspace, and nothing in this library ever executes workspace content.

### Error handling

Everything that stops a run short of a normal completion goes to `onError`, not thrown from `run(...)` (which itself only validates its input synchronously before dispatching the run):

- `UnregisteredToolException` — the model called a tool that isn't registered, and `onUnregisteredTool` didn't resolve it.
- `AgentLoopStepLimitExceededException` — the run exceeded `AgentProfile.maxSteps()`.
- Any exception a tool handler throws, other than `ToolInputException` (which goes back to the model instead).
- Anything `llm-router` itself throws (e.g. `RouterExhaustedException` if every candidate provider/model fails — including when none of them can call tools, since every call that offers tools requires a tool-capable model).

Cost and time bounds (`LoopRequest.maxCostUsdCents`/`maxDuration`) are the exception — those stop the run gracefully via `onResult`, not `onError`, as described above.

## Extending the library: your own memory, workspace and tools

Everything an agent touches outside the model is pluggable: where it remembers things (`MemoryStore`), where it keeps files (`Workspace`), and what it can do (tools). This section is the contract for writing your own.

### Rules that apply to everything below

- **One instance serves every user.** A `MemoryStore`, `Workspace` or tool handler is shared by all runs of all users, so:
  - never keep per-user or per-run state in fields;
  - always partition by the `Scope` (stores) or read it from the `ToolContext` (tools).
- **Calls are concurrent and run on virtual threads.** Be thread-safe. Blocking I/O (JDBC, HTTP, S3) is fine and expected — no need for async APIs.
- **The scope you receive is already reduced to the configured level.** At the default `ScopeLevel.USER`, `scope.sessionId()` is `null`. Don't apply levels yourself; store and look up data under exactly the scope you're given.
- **Use `scope.key()` as your storage key.** It's a stable string such as `acme/alice/*`, with each id URL-encoded so ids containing `/` can't collide with others or escape a prefix. Two scopes have the same key exactly when they're equal, so it works directly as a database column, an S3 prefix, or a cache key.

### A custom `MemoryStore`

Three methods:

- `save` stores a fact and assigns it an id.
- `search` returns the most relevant entries, best first. A **blank query means most recent first**: `memory_search` with no query relies on that.
- `delete` returns `false` when the id doesn't exist *in that scope*. Never delete across scopes.

An example over Postgres full-text search:

```java
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;

public final class PostgresMemoryStore implements MemoryStore {

    // CREATE TABLE agent_memory (id text PRIMARY KEY, scope_key text NOT NULL, content text NOT NULL,
    //                            tags text[] NOT NULL, created_at timestamptz NOT NULL);
    // CREATE INDEX ON agent_memory (scope_key, created_at DESC);
    private final DataSource db;

    public PostgresMemoryStore(DataSource db) {
        this.db = db;
    }

    @Override
    public MemoryEntry save(Scope scope, String content, List<String> tags) {
        MemoryEntry entry = new MemoryEntry(UUID.randomUUID().toString(), content, tags, Instant.now());
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO agent_memory (id, scope_key, content, tags, created_at) VALUES (?, ?, ?, ?, ?)")) {
            s.setString(1, entry.id());
            s.setString(2, scope.key());
            s.setString(3, content);
            s.setArray(4, c.createArrayOf("text", entry.tags().toArray()));
            s.setTimestamp(5, Timestamp.from(entry.createdAt()));
            s.executeUpdate();
            return entry;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save memory", e);
        }
    }

    @Override
    public List<MemoryEntry> search(Scope scope, String query, int limit) {
        String sql = query.isBlank()
                ? "SELECT * FROM agent_memory WHERE scope_key = ? ORDER BY created_at DESC LIMIT ?"
                : "SELECT * FROM agent_memory WHERE scope_key = ? "
                        + "AND to_tsvector('english', content) @@ plainto_tsquery('english', ?) "
                        + "ORDER BY ts_rank(to_tsvector('english', content), plainto_tsquery('english', ?)) DESC LIMIT ?";
        // ... bind scope.key(), (query, query,) limit; map each row to a MemoryEntry
    }

    @Override
    public boolean delete(Scope scope, String id) {
        // DELETE FROM agent_memory WHERE id = ? AND scope_key = ?   — the scope_key condition is essential
        // return rowsAffected == 1;
    }
}

AgentLoop loop = AgentLoop.builder().memory(new PostgresMemoryStore(dataSource)) /* ... */ .build();
```

For semantic recall, swap the full-text query for an embedding search (pgvector, Pinecone, etc.) and keep the same filter on `scope_key`. The loop calls `search` with each step's goal to recall context automatically, and the model calls it via `memory_search`; both go through this one method.

### A custom `Workspace`

Four methods: `read`, `write` (create or replace), `delete` and `list`. **Path safety and limits are already handled before your code is called.** `ScopedWorkspace` normalizes every path (relative, `/`-separated, no `..`, no absolute paths or drive letters) and enforces `WorkspaceLimits` first. So an implementation only maps `(scope, path)` to storage. Files can be binary: keep the bytes and the media type exactly as given.

An example over S3, using AWS SDK v2:

```java
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.workspace.Workspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFileInfo;

public final class S3Workspace implements Workspace {

    private final S3Client s3;
    private final String bucket;

    public S3Workspace(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    private static String prefix(Scope scope) {
        return "workspaces/" + scope.key() + "/";
    }

    @Override
    public Optional<WorkspaceFile> read(Scope scope, String path) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    b -> b.bucket(bucket).key(prefix(scope) + path));
            GetObjectResponse meta = object.response();
            return Optional.of(new WorkspaceFile(path, meta.contentType(), object.asByteArray(), meta.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void write(Scope scope, WorkspaceFile file) {
        s3.putObject(b -> b.bucket(bucket).key(prefix(scope) + file.path()).contentType(file.mediaType()),
                RequestBody.fromBytes(file.content()));
    }

    @Override
    public boolean delete(Scope scope, String path) {
        if (read(scope, path).isEmpty()) {
            return false;
        }
        s3.deleteObject(b -> b.bucket(bucket).key(prefix(scope) + path));
        return true;
    }

    @Override
    public List<WorkspaceFileInfo> list(Scope scope) {
        String prefix = prefix(scope);
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(o -> new WorkspaceFileInfo(o.key().substring(prefix.length()),
                        MediaTypes.guess(o.key(), MediaTypes.OCTET_STREAM), o.size(), o.lastModified()))
                .toList();
    }
}
```

`list` is called on every write to check the limits, so keep it cheap. For very large workspaces, a database index of file metadata beside the blob store works better than listing a bucket.

### A custom tool

A tool is a `ToolDefinition` (what the model sees) plus a `ToolHandler` (what runs):

```java
tools.register(
    ToolDefinition.builder()
        .name("crm_find_contact")                       // ^[a-zA-Z0-9_-]{1,64}$, unique within the registry
        .description("Find a contact in the company CRM by name or email. Returns at most 10 matches.")
        .parameters(Map.of(
            "type", "object",                           // top level must be an object
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "Name or email to search for")),
            "required", List.of("query")))
        .build(),
    (arguments, context) -> {
        Object query = arguments.get("query");
        if (!(query instanceof String text) || text.isBlank()) {
            throw new ToolInputException("'query' is required");      // goes back to the model; run continues
        }
        CrmClient crm = crmClients.forTenant(context.scope().tenantId()); // per-tenant credentials, never from the model
        List<Contact> matches = crm.search(text, 10);
        return matches.isEmpty() ? "No contacts found." : toJson(matches);
    });
```

Guidelines:

- **Writing the definition:**
  - Keep the name within `^[a-zA-Z0-9_-]{1,64}$` and unique. `llm-router` checks this before any call and rejects invalid definitions.
  - Stick to the JSON-schema subset every provider accepts: a top-level `object`, simple property types, an explicit `required` list, and no `$ref`/`oneOf`.
  - Write the description for the model. Say what the tool does, when to use it, and what it returns.
- **Whose data a call is for comes from `context`, never from arguments.** Credentials, tenant and user all come from `context.scope()`. If the model could name a user in an argument, it could reach another user's data. `context.memory()` and `context.workspace()` are already bound to the run's scope, so tools that read or write the agent's memory or files should use them.
- **Two kinds of failure:**
  - Throw `ToolInputException` when the model can fix the problem (a bad argument, a record not found, a 404). It becomes an `"Error: ..."` result and the run carries on.
  - Let other exceptions propagate when the problem is real infrastructure failure. They end the run via `onError`. The loop never retries a tool itself.
- **Keep results small and readable.** Everything returned goes into the model's context. Cap result sizes and paginate (see `workspace_read`'s `offset`), and prefer compact JSON or plain text over raw API dumps.
- **Blocking is fine.** Handlers run on the run's virtual thread, so a tool can call a slow API, or wait for a human.

The built-in `MemoryTools` and `WorkspaceTools` in `io.github.manishpateluk.llmagentloop.tool.builtin` are written exactly this way and make good reference implementations.

## Learn more

This README only covers installing and calling the library. For the overall design — the full behavior spec is still taking shape — see the [project README](../README.md) at the repo root, which is kept up to date as each piece is implemented.

## Developer notes: publishing to Maven Central

1. Bump the version in `java/pom.xml` to the new release, e.g. `1.0.1`
2. From `java/`: `mvn clean deploy -Prelease`
3. It'll prompt for your GPG passphrase, sign everything, and upload the bundle to Central
4. Go to the Central Portal → Deployments, find it, review the contents, and click Publish — it stays private until you do this
5. It typically takes 15–30 minutes to sync out to Maven Central and search.maven.org after you publish
