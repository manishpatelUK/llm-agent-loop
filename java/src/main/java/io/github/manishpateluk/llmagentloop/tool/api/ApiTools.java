package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code api_request}: generic, authenticated HTTP calls to APIs the implementor has registered
 * as {@link ApiConnection}s — the way to give an agent a company's internal systems, CRM, billing
 * or any REST API without writing a tool per endpoint.
 *
 * <pre>{@code
 * registry.register(ApiTools.request(List.of(stripe, crm)));        // the same connections for every run
 * registry.registerAll(ApiTools.all(scope -> connectionsFor(scope)));  // or each tenant's own, looked up per run
 * }</pre>
 *
 * <p>The model chooses a connection, method and path; the tool builds the URL under the
 * connection's base URL, adds its credentials, and returns the status and (trimmed) body. 4xx/5xx
 * responses come back as ordinary results so the model can correct itself. Being unable to reach
 * the API at all (connection refused, timeout) is an infrastructure failure and ends the run.
 * Redirects are not followed: a 3xx comes back with its {@code Location}.
 *
 * <p>With a fixed list, the connections are named in the tool's description. With a
 * {@link ConnectionSource}, they can differ per run, so the tool definitions stay the same for
 * everyone (and cacheable) and the model lists the run's connections with {@code api_connections}.
 */
public final class ApiTools {

    public static final String REQUEST = "api_request";
    public static final String CONNECTIONS = "api_connections";
    public static final String DESCRIBE = "api_describe";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Headers the model's response view never shows — they can carry session tokens. */
    private static final Set<String> HIDDEN_RESPONSE_HEADERS = Set.of("set-cookie", "authorization", "www-authenticate");
    private static final RegisteredTool.WriteCheck WRITES = (args, context) -> {
        Object method = args.get("method");
        return !ApiConnection.READ_METHODS.contains(method == null ? "" : method.toString().toUpperCase(Locale.ROOT));
    };

    private ApiTools() {
    }

    /** {@code api_request} over a fixed set of connections, listed in its description. */
    public static RegisteredTool request(List<ApiConnection> connections) {
        if (connections == null || connections.isEmpty()) {
            throw new IllegalArgumentException("At least one connection is required");
        }
        ConnectionSource source = ConnectionSource.of(connections);
        Set<String> methods = new TreeSet<>();
        StringBuilder roster = new StringBuilder();
        for (ApiConnection connection : connections) {
            methods.addAll(connection.allowedMethods());
            roster.append("\n- ").append(summary(connection));
        }
        List<String> names = connections.stream().map(ApiConnection::name).toList();
        String description = "Call an HTTP API through a pre-configured connection; authentication is "
                + "handled for you. Paths are relative to the connection's base URL. Use 'select' to "
                + "return just part of a large JSON response. Connections:" + roster;
        return request(source, description, ToolSchemas.stringEnum("Which API.", names), List.copyOf(methods));
    }

    /**
     * {@code api_request} over each run's own connections, looked up from its scope on every call.
     * Register {@link #connections} too (or use {@link #all}) so the model can see what they are.
     */
    public static RegisteredTool request(ConnectionSource connections) {
        String description = "Call an HTTP API through one of the connections set up for this user; "
                + "authentication is handled for you. Call " + CONNECTIONS + " first to see the connections "
                + "and what each allows. Paths are relative to the connection's base URL. Use 'select' to "
                + "return just part of a large JSON response.";
        return request(connections, description, ToolSchemas.string("Which connection, by name (see " + CONNECTIONS + ")."),
                List.copyOf(new TreeSet<>(ApiConnection.ALL_METHODS)));
    }

    /** {@code api_connections}: the run's connections, with what each allows. */
    public static RegisteredTool connections(ConnectionSource connections) {
        Objects.requireNonNull(connections, "connections");
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(CONNECTIONS)
                        .description("List the API connections available to you: each one's name, what it's for, "
                                + "and which methods and paths it allows. Use the names with " + REQUEST + ".")
                        .parameters(ToolSchemas.object(List.of()))
                        .build(),
                (args, context) -> {
                    Map<String, ApiConnection> byName = connections.byName(context.scope());
                    if (byName.isEmpty()) {
                        return "No API connections are set up for this user.";
                    }
                    StringBuilder out = new StringBuilder("Connections:");
                    byName.values().forEach(c -> {
                        out.append("\n- ").append(summary(c));
                        if (c.openApiSpec() != null) {
                            out.append(" [OpenAPI description: use " + DESCRIBE + "]");
                        }
                    });
                    return out.toString();
                });
    }

    /**
     * {@code api_describe}: a compact, searchable list of a connection's endpoints, read from the
     * OpenAPI description set with {@link ApiConnection.Builder#openApiSpec} — so the model needn't
     * read HTML documentation.
     */
    public static RegisteredTool describe(ConnectionSource connections) {
        Objects.requireNonNull(connections, "connections");
        OpenApiEndpoints endpoints = new OpenApiEndpoints();
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(DESCRIBE)
                        .description("List a connection's endpoints from its OpenAPI description: method, path, "
                                + "summary, parameters and body fields. Filter with 'query' (words matched against "
                                + "paths, summaries and tags); page on with 'offset'.")
                        .parameters(ToolSchemas.object(List.of("connection"),
                                "connection", ToolSchemas.string("Which connection, by name."),
                                "query", ToolSchemas.string("Optional words to filter endpoints by, e.g. \"invoice\"."),
                                "offset", ToolSchemas.integer("Optional: skip this many matching endpoints.")))
                        .build(),
                (args, context) -> {
                    ApiConnection connection = connection(connections, ToolArguments.requireString(args, "connection"), context);
                    return endpoints.describe(connection, ToolArguments.optionalString(args, "query"),
                            ToolArguments.optionalInt(args, "offset", 0, 0, 100_000), context.scope());
                })
                .withUntrustedOutput();
    }

    /** {@code api_connections}, {@code api_request} and {@code api_describe} over each run's own connections. */
    public static List<RegisteredTool> all(ConnectionSource connections) {
        return List.of(connections(connections), request(connections), describe(connections));
    }

    /** One connection as listed for the model. */
    static String summary(ApiConnection connection) {
        StringBuilder out = new StringBuilder(connection.name());
        if (!connection.description().isBlank()) {
            out.append(": ").append(connection.description());
        }
        out.append(" (methods: ").append(String.join(", ", new TreeSet<>(connection.allowedMethods())))
                .append("; paths: ").append(String.join(", ", connection.allowedPaths()));
        if (connection.bodyType() != ApiBodyType.JSON) {
            out.append("; bodies: ").append(connection.bodyType().mediaType());
        }
        return out.append(')').toString();
    }

    private static RegisteredTool request(ConnectionSource source, String description, Map<String, Object> connectionSchema,
                                          List<String> methods) {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(REQUEST)
                        .description(description)
                        .parameters(ToolSchemas.object(List.of("connection", "method", "path"),
                                "connection", connectionSchema,
                                "method", ToolSchemas.stringEnum("HTTP method.", methods),
                                "path", ToolSchemas.string("Path under the base URL, starting with '/', e.g. \"/customers/cus_123\". No query string."),
                                "query", ToolSchemas.array("Query parameters.", nameValue()),
                                "body", ToolSchemas.string("Request body as JSON text (or plain text with content_type text/plain), for POST/PUT/PATCH."),
                                "form", ToolSchemas.array("Form fields, for APIs that take application/x-www-form-urlencoded bodies, "
                                        + "e.g. [{\"name\":\"email\",\"value\":\"a@b.com\"},{\"name\":\"metadata[plan]\",\"value\":\"pro\"}].", nameValue()),
                                "content_type", ToolSchemas.stringEnum("Optional body encoding, overriding the connection's default.",
                                        ApiBodyType.mediaTypes()),
                                "select", ToolSchemas.string("Optional JSON Pointer into the response, e.g. \"/data/0/id\"."),
                                "save_as", ToolSchemas.string("Optional workspace path to save the raw response body to.")))
                        .build(),
                (args, context) -> call(source, args, context))
                .withUntrustedOutput()
                .withWriteCheck(WRITES)
                .withWorkspaceParameters(List.of("save_as"), description);
    }

    static Map<String, Object> nameValue() {
        return ToolSchemas.object(List.of("name", "value"),
                "name", ToolSchemas.string("Name."),
                "value", ToolSchemas.string("Value."));
    }

    static ApiConnection connection(ConnectionSource source, String name, ToolContext context) {
        Map<String, ApiConnection> byName = source.byName(context.scope());
        ApiConnection connection = byName.get(name);
        if (connection == null) {
            throw new ToolInputException("No connection called '" + name + "'; available: " + byName.keySet());
        }
        return connection;
    }

    private static String call(ConnectionSource source, Map<String, Object> args, ToolContext context) {
        ApiConnection connection = connection(source, ToolArguments.requireString(args, "connection"), context);
        String method = ToolArguments.requireString(args, "method").toUpperCase(Locale.ROOT);
        ApiHttp.checkMethod(connection, method);
        String path = ToolArguments.requireString(args, "path").strip();
        Map<String, String> query = new LinkedHashMap<>();
        pairs(args, "query").forEach(e -> query.put(e.getKey(), e.getValue()));
        ApiHttp.resolve(connection, path, query);   // refuse a bad path before looking at the body
        ApiHttp.Body body = body(connection, method, args);
        HttpResponse<byte[]> response = ApiHttp.send(connection, method, path, query, body, context.scope(), REQUEST);
        return describe(connection, method, path, response, args, context);
    }

    /** The request body the arguments describe, checked against its encoding; {@code null} for none. */
    private static ApiHttp.Body body(ApiConnection connection, String method, Map<String, Object> args) {
        String text = ToolArguments.optionalString(args, "body");
        boolean hasText = text != null && !text.isBlank();
        List<Map.Entry<String, String>> form = args.get("form") == null ? null : pairs(args, "form");
        boolean hasForm = form != null && !form.isEmpty();
        if (!hasText && !hasForm) {
            return null;
        }
        if (ApiConnection.READ_METHODS.contains(method)) {
            throw new ToolInputException(method + " requests can't have a body");
        }
        String contentType = ToolArguments.optionalString(args, "content_type");
        ApiBodyType type;
        if (contentType != null && !contentType.isBlank()) {
            type = ApiBodyType.ofMediaType(contentType).orElseThrow(() -> new ToolInputException(
                    "'content_type' must be one of " + ApiBodyType.mediaTypes()));
        } else {
            type = hasForm ? ApiBodyType.FORM : connection.bodyType();
        }
        return switch (type) {
            case FORM -> {
                if (hasText) {
                    throw new ToolInputException("Form bodies are given as 'form' name/value pairs, not 'body' text"
                            + (hasForm ? "" : "; or set content_type to send 'body' as JSON or plain text"));
                }
                yield new ApiHttp.Body(type, ApiHttp.formEncode(form));
            }
            case JSON, TEXT -> {
                if (hasForm) {
                    throw new ToolInputException("'form' fields need content_type " + ApiBodyType.FORM.mediaType()
                            + "; use 'body' for " + type.mediaType());
                }
                if (type == ApiBodyType.JSON) {
                    try {
                        JSON.readTree(text);
                    } catch (JacksonException e) {
                        throw new ToolInputException("'body' must be valid JSON: " + e.getOriginalMessage());
                    }
                }
                yield new ApiHttp.Body(type, text);
            }
        };
    }

    /** A list of {@code {name, value}} arguments, in order; empty when absent. */
    static List<Map.Entry<String, String>> pairs(Map<String, Object> args, String name) {
        Object value = args.get(name);
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (value == null) {
            return pairs;
        }
        if (!(value instanceof List<?> list)) {
            throw new ToolInputException("'" + name + "' must be a list of {name, value} objects");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> pair) || !(pair.get("name") instanceof String key) || key.isBlank()) {
                throw new ToolInputException("Each '" + name + "' entry needs a 'name' and a 'value'");
            }
            Object v = pair.get("value");
            pairs.add(new AbstractMap.SimpleImmutableEntry<>(key, v == null ? "" : String.valueOf(v)));
        }
        return pairs;
    }

    private static String describe(ApiConnection connection, String method, String path, HttpResponse<byte[]> response,
                                   Map<String, Object> args, ToolContext context) {
        String mediaType = ApiHttp.mediaType(response);
        StringBuilder out = new StringBuilder("HTTP ").append(response.statusCode()).append(' ').append(method).append(' ').append(path).append('\n');
        if (!mediaType.isEmpty()) {
            out.append("Content-Type: ").append(mediaType).append('\n');
        }
        response.headers().firstValue("Location").ifPresent(location -> out.append("Location: ").append(location).append('\n'));
        response.headers().map().keySet().stream()
                .filter(h -> h.toLowerCase(Locale.ROOT).startsWith("x-ratelimit-remaining"))
                .filter(h -> !HIDDEN_RESPONSE_HEADERS.contains(h.toLowerCase(Locale.ROOT)))
                .forEach(h -> out.append(h).append(": ").append(response.headers().firstValue(h).orElse("")).append('\n'));

        byte[] bytes = response.body();
        String saveAs = ToolArguments.optionalString(args, "save_as");
        if (saveAs != null && !saveAs.isBlank()) {
            try {
                WorkspaceFile saved = context.workspace().write(saveAs, bytes, mediaType.isEmpty() ? null : mediaType);
                out.append("Saved ").append(saved.size()).append(" bytes to ").append(saved.path()).append(".\n");
            } catch (WorkspaceException e) {
                throw new ToolInputException(e.getMessage());
            }
        }
        if (bytes.length == 0) {
            return out.append("(empty body)").toString();
        }

        boolean json = mediaType.contains("json");
        if (!json && !mediaType.isEmpty() && !MediaTypes.isText(mediaType)) {
            out.append("(").append(bytes.length).append(" bytes of ").append(mediaType);
            if (context.workspace().isConfigured()) {
                out.append("; use save_as to keep it in the workspace");
            }
            return out.append(")").toString();
        }
        String text = ApiHttp.text(response);
        String select = ToolArguments.optionalString(args, "select");
        if (json) {
            try {
                JsonNode node = JSON.readTree(text);
                if (select != null && !select.isBlank()) {
                    JsonNode selected = node.at(select.strip());
                    if (selected.isMissingNode()) {
                        throw new ToolInputException("Nothing at '" + select + "' in the response");
                    }
                    node = selected;
                }
                text = JSON.writeValueAsString(node);
            } catch (JacksonException e) {
                // Mislabelled JSON: show it as text.
            } catch (IllegalArgumentException e) {
                throw new ToolInputException("'select' must be a JSON Pointer like \"/data/0/id\"");
            }
        }
        out.append('\n');
        return out.append(capped(text, connection.maxResponseChars(),
                "use 'select', query parameters such as a page size, or save_as")).toString();
    }

    /** {@code text}, cut off at {@code max} characters with a note saying how to get less. */
    static String capped(String text, int max, String hint) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n[Response cut off at " + max + " of " + text.length() + " characters; " + hint + ".]";
    }
}
