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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code api_request}: generic, authenticated HTTP calls to APIs the implementor has registered
 * as {@link ApiConnection}s — the way to give an agent a company's internal systems, CRM, billing
 * or any REST API without writing a tool per endpoint.
 *
 * <pre>{@code
 * registry.register(ApiTools.request(List.of(stripe, crm)));
 * }</pre>
 *
 * <p>The model chooses a connection, method and path; the tool builds the URL under the
 * connection's base URL, adds its credentials, and returns the status and (trimmed) body. 4xx/5xx
 * responses come back as ordinary results so the model can correct itself. Being unable to reach
 * the API at all (connection refused, timeout) is an infrastructure failure and ends the run.
 * Redirects are not followed: a 3xx comes back with its {@code Location}.
 */
public final class ApiTools {

    public static final String REQUEST = "api_request";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Headers the model's response view never shows — they can carry session tokens. */
    private static final Set<String> HIDDEN_RESPONSE_HEADERS = Set.of("set-cookie", "authorization", "www-authenticate");

    private ApiTools() {
    }

    public static RegisteredTool request(List<ApiConnection> connections) {
        if (connections == null || connections.isEmpty()) {
            throw new IllegalArgumentException("At least one connection is required");
        }
        Map<String, ApiConnection> byName = new LinkedHashMap<>();
        Set<String> methods = new TreeSet<>();
        StringBuilder roster = new StringBuilder();
        for (ApiConnection connection : connections) {
            if (byName.put(connection.name(), connection) != null) {
                throw new IllegalArgumentException("Duplicate connection name: " + connection.name());
            }
            methods.addAll(connection.allowedMethods());
            roster.append("\n- ").append(connection.name());
            if (!connection.description().isBlank()) {
                roster.append(": ").append(connection.description());
            }
            roster.append(" (methods: ").append(String.join(", ", new TreeSet<>(connection.allowedMethods())))
                    .append("; paths: ").append(String.join(", ", connection.allowedPaths())).append(')');
        }

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(REQUEST)
                        .description("Call an HTTP API through a pre-configured connection; authentication is "
                                + "handled for you. Paths are relative to the connection's base URL. Use 'select' to "
                                + "return just part of a large JSON response. Connections:" + roster)
                        .parameters(ToolSchemas.object(List.of("connection", "method", "path"),
                                "connection", ToolSchemas.stringEnum("Which API.", List.copyOf(byName.keySet())),
                                "method", ToolSchemas.stringEnum("HTTP method.", List.copyOf(methods)),
                                "path", ToolSchemas.string("Path under the base URL, starting with '/', e.g. \"/customers/cus_123\". No query string."),
                                "query", ToolSchemas.array("Query parameters.", ToolSchemas.object(List.of("name", "value"),
                                        "name", ToolSchemas.string("Parameter name."),
                                        "value", ToolSchemas.string("Parameter value.")))
                                ,
                                "body", ToolSchemas.string("Request body as JSON text, for POST/PUT/PATCH."),
                                "select", ToolSchemas.string("Optional JSON Pointer into the response, e.g. \"/data/0/id\"."),
                                "save_as", ToolSchemas.string("Optional workspace path to save the raw response body to.")))
                        .build(),
                (args, context) -> call(client, byName, args, context));
    }

    private static String call(HttpClient client, Map<String, ApiConnection> byName, Map<String, Object> args, ToolContext context) {
        String name = ToolArguments.requireString(args, "connection");
        ApiConnection connection = byName.get(name);
        if (connection == null) {
            throw new ToolInputException("No connection called '" + name + "'; available: " + byName.keySet());
        }
        String method = ToolArguments.requireString(args, "method").toUpperCase(Locale.ROOT);
        if (!connection.allowedMethods().contains(method)) {
            throw new ToolInputException(method + " isn't allowed on " + name + "; allowed: " + new TreeSet<>(connection.allowedMethods()));
        }
        String path = ToolArguments.requireString(args, "path").strip();
        URI uri = resolve(connection, path, queryParameters(connection, args, context));

        String body = ToolArguments.optionalString(args, "body");
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null && !body.isBlank()) {
            if (method.equals("GET") || method.equals("HEAD")) {
                throw new ToolInputException(method + " requests can't have a body");
            }
            try {
                JSON.readTree(body);
            } catch (JacksonException e) {
                throw new ToolInputException("'body' must be valid JSON: " + e.getOriginalMessage());
            }
            publisher = HttpRequest.BodyPublishers.ofString(body);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(connection.timeout())
                .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
                .method(method, publisher);
        if (body != null && !body.isBlank()) {
            request.header("Content-Type", "application/json");
        }
        Map<String, String> headers = new LinkedHashMap<>(connection.defaultHeaders());
        headers.putAll(connection.auth().headers(context.scope()));
        headers.forEach(request::setHeader);

        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("api_request to connection '" + name + "' failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("api_request to connection '" + name + "' was interrupted", e);
        }
        return describe(connection, method, path, response, args, context);
    }

    /**
     * The connection's base URL plus a model-supplied path, refusing anything that could leave it:
     * a different host, {@code ..} segments (encoded or not), backslashes, or an embedded query or fragment.
     */
    static URI resolve(ApiConnection connection, String path, Map<String, String> query) {
        if (!path.startsWith("/")) {
            throw new ToolInputException("'path' must start with '/'");
        }
        if (path.contains("?") || path.contains("#")) {
            throw new ToolInputException("'path' must not contain '?' or '#'; pass query parameters in 'query'");
        }
        if (path.contains("\\") || path.contains("//") || path.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new ToolInputException("'path' contains characters that aren't allowed");
        }
        URI base = connection.baseUrl();
        StringBuilder url = new StringBuilder(base.toString()).append(path);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }
        }
        URI uri;
        try {
            uri = new URI(url.toString());
        } catch (URISyntaxException e) {
            throw new ToolInputException("Invalid path (characters may need percent-encoding): " + path);
        }
        String decodedPath = uri.getPath() == null ? "" : uri.getPath();
        for (String segment : decodedPath.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new ToolInputException("'path' must not contain '.' or '..' segments");
            }
        }
        String basePath = base.getPath() == null ? "" : base.getPath();
        if (!uri.getScheme().equalsIgnoreCase(base.getScheme()) || !uri.getHost().equalsIgnoreCase(base.getHost())
                || uri.getPort() != base.getPort() || !decodedPath.startsWith(basePath)) {
            throw new ToolInputException("'path' must stay under " + base);
        }
        String relative = decodedPath.substring(basePath.length());
        if (!connection.pathAllowed(relative.isEmpty() ? "/" : relative)) {
            throw new ToolInputException(relative + " isn't an allowed path on " + connection.name()
                    + "; allowed: " + connection.allowedPaths());
        }
        return uri;
    }

    private static Map<String, String> queryParameters(ApiConnection connection, Map<String, Object> args, ToolContext context) {
        Map<String, String> query = new LinkedHashMap<>();
        Object value = args.get("query");
        if (value != null) {
            if (!(value instanceof List<?> list)) {
                throw new ToolInputException("'query' must be a list of {name, value} objects");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> pair) || !(pair.get("name") instanceof String key) || key.isBlank()) {
                    throw new ToolInputException("Each 'query' entry needs a 'name' and a 'value'");
                }
                Object v = pair.get("value");
                query.put(key, v == null ? "" : String.valueOf(v));
            }
        }
        query.putAll(connection.auth().queryParameters(context.scope()));
        return query;
    }

    private static String describe(ApiConnection connection, String method, String path, HttpResponse<byte[]> response,
                                   Map<String, Object> args, ToolContext context) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String mediaType = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
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
            return out.append("(").append(bytes.length).append(" bytes of ").append(mediaType)
                    .append("; use save_as to keep it in the workspace)").toString();
        }
        String text = new String(bytes, charset(contentType));
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
        if (text.length() > connection.maxResponseChars()) {
            out.append(text, 0, connection.maxResponseChars())
                    .append("\n[Response cut off at ").append(connection.maxResponseChars()).append(" of ").append(text.length())
                    .append(" characters; use 'select', query parameters such as a page size, or save_as.]");
        } else {
            out.append(text);
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Charset charset(String contentType) {
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(p.substring(8).replace("\"", "").strip());
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
