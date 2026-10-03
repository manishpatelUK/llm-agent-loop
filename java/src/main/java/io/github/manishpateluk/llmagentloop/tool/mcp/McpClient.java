package io.github.manishpateluk.llmagentloop.tool.mcp;

import com.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A client for one <a href="https://modelcontextprotocol.io">Model Context Protocol</a> server,
 * exposing its tools to an agent — so anything with an MCP server (GitHub, Slack, Google Drive,
 * databases, internal services written in any language) becomes agent tooling without a Java
 * tool per capability.
 *
 * <pre>{@code
 * McpClient github = McpClient.http("github", URI.create("https://api.githubcopilot.com/mcp/"),
 *         Map.of("Authorization", "Bearer " + token));
 * McpClient files = McpClient.stdio("files", List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"));
 * registry.registerAll(github.tools()).registerAll(files.tools());
 * // ... and close() the clients on shutdown
 * }</pre>
 *
 * <p>Supports what agents need: the initialize handshake, {@code tools/list} (with pagination) and
 * {@code tools/call}, over stdio or Streamable HTTP. Tool names are prefixed with the client's name
 * ({@code github_create_issue}) to avoid clashes between servers, and adjusted to the
 * {@code ^[a-zA-Z0-9_-]{1,64}$} pattern providers require. A tool result flagged {@code isError}
 * comes back to the model as a fixable error; a server that can't be reached ends the run.
 *
 * <p>One client holds one connection with one set of credentials. If tenants need different
 * credentials for the same server, create a client per tenant. Thread-safe; connects lazily on
 * first use.
 */
public final class McpClient implements AutoCloseable {

    /** Offered at initialization; the server may answer with another version, which is then used. */
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String name;
    private final McpTransport transport;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean initialized;
    private volatile String serverName = "";

    McpClient(String name, McpTransport transport, Duration timeout) {
        if (name == null || !name.matches("^[a-zA-Z0-9_-]{1,32}$")) {
            throw new IllegalArgumentException("MCP client name must match ^[a-zA-Z0-9_-]{1,32}$: " + name);
        }
        this.name = name;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** Launches {@code command} as a local MCP server speaking stdio. */
    public static McpClient stdio(String name, List<String> command) {
        return stdio(name, new ProcessBuilder(command), Duration.ofSeconds(60));
    }

    /** Full control over the process (working directory, environment variables) and the per-call timeout. */
    public static McpClient stdio(String name, ProcessBuilder process, Duration timeout) {
        return new McpClient(name, new StdioTransport(process), timeout);
    }

    /** A remote MCP server over Streamable HTTP; {@code headers} (e.g. {@code Authorization}) go on every request. */
    public static McpClient http(String name, URI endpoint, Map<String, String> headers) {
        return http(name, endpoint, headers, Duration.ofSeconds(60));
    }

    public static McpClient http(String name, URI endpoint, Map<String, String> headers, Duration timeout) {
        return new McpClient(name, new HttpTransport(endpoint, headers == null ? Map.of() : headers), timeout);
    }

    public String name() {
        return name;
    }

    /** One {@link McpTool} per server tool, fetched now (connecting if needed). */
    public List<McpTool> listTools() {
        ensureInitialized();
        List<McpTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = call("tools/list", params);
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpTool(tool.path("name").asString(), tool.path("description").asString(""),
                        schema(tool.path("inputSchema"))));
            }
            cursor = result.path("nextCursor").isString() ? result.path("nextCursor").asString() : null;
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    /**
     * @param name        the tool's name on the server
     * @param description the server's description
     * @param inputSchema its JSON-schema parameters, as a plain map
     */
    public record McpTool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** Calls a server tool directly and returns its result rendered as text. */
    public String callTool(String toolName, Map<String, Object> arguments) {
        ensureInitialized();
        ObjectNode params = JSON.createObjectNode().put("name", toolName);
        params.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = call("tools/call", params);
        String text = render(result);
        if (result.path("isError").asBoolean(false)) {
            throw new ToolInputException(text.isBlank() ? toolName + " failed" : text);
        }
        return text.isBlank() ? "(no output)" : text;
    }

    /** This server's tools as agent tools, named {@code <client name>_<tool name>}. */
    public List<RegisteredTool> tools() {
        List<RegisteredTool> registered = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (McpTool tool : listTools()) {
            String agentName = uniqueName(name + "_" + tool.name(), used);
            String description = tool.description().isBlank()
                    ? "Tool '" + tool.name() + "' from the " + (serverName.isBlank() ? name : serverName) + " MCP server."
                    : tool.description();
            registered.add(new RegisteredTool(
                    ToolDefinition.builder().name(agentName).description(description).parameters(tool.inputSchema()).build(),
                    (args, context) -> callTool(tool.name(), args)));
        }
        return registered;
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        ObjectNode params = JSON.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "llm-agent-loop").put("version", "1.0");
        JsonNode result = call("initialize", params);
        String version = result.path("protocolVersion").asString(PROTOCOL_VERSION);
        transport.protocolVersion(version);
        serverName = result.path("serverInfo").path("name").asString("");
        transport.notify(JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        initialized = true;
    }

    private JsonNode call(String method, ObjectNode params) {
        ObjectNode request = JSON.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", String.valueOf(ids.incrementAndGet()))
                .put("method", method);
        request.set("params", params);
        ObjectNode response = transport.request(request, timeout);
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asString("unknown error");
            if (method.equals("tools/call")) {
                // e.g. unknown tool or invalid arguments: something the model can correct
                throw new ToolInputException(message);
            }
            throw new McpException("MCP server '" + name + "' refused " + method + ": " + message);
        }
        return response.path("result");
    }

    /** Text content joined; non-text content described; structured content as JSON when there's no text. */
    private static String render(JsonNode result) {
        List<String> parts = new ArrayList<>();
        for (JsonNode item : result.path("content")) {
            String type = item.path("type").asString("");
            switch (type) {
                case "text" -> parts.add(item.path("text").asString(""));
                case "image", "audio" -> parts.add("[" + type + ": " + item.path("mimeType").asString("unknown type")
                        + ", " + (item.path("data").asString("").length() * 3 / 4) + " bytes]");
                case "resource" -> {
                    JsonNode resource = item.path("resource");
                    parts.add(resource.has("text") ? resource.path("text").asString("")
                            : "[resource: " + resource.path("uri").asString("") + "]");
                }
                case "resource_link" -> parts.add("[resource: " + item.path("uri").asString("")
                        + (item.has("name") ? " (" + item.path("name").asString("") + ")" : "") + "]");
                default -> parts.add(JSON.writeValueAsString(item));
            }
        }
        if (parts.isEmpty() && result.has("structuredContent")) {
            parts.add(JSON.writeValueAsString(result.get("structuredContent")));
        }
        return String.join("\n", parts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(JsonNode inputSchema) {
        Map<String, Object> schema = inputSchema.isObject()
                ? new LinkedHashMap<>(JSON.convertValue(inputSchema, Map.class))
                : new LinkedHashMap<>();
        schema.putIfAbsent("type", "object");
        schema.putIfAbsent("properties", Map.of());
        return schema;
    }

    /** Provider-safe, at most 64 characters, and unique among this client's tools. */
    static String uniqueName(String raw, Set<String> used) {
        String safe = UNSAFE.matcher(raw).replaceAll("_");
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        String candidate = safe;
        for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            String suffix = "_" + n;
            candidate = safe.substring(0, Math.min(safe.length(), 64 - suffix.length())) + suffix;
        }
        return candidate;
    }

    @Override
    public void close() {
        transport.close();
    }
}
