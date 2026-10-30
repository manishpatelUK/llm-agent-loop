package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behind {@code api_describe}: reads a connection's OpenAPI 3 or Swagger 2 description (JSON) and
 * lists its endpoints compactly — method, path, summary, parameters and top-level body fields —
 * leaving out those the connection doesn't allow. Paths are made relative to the connection's
 * base URL, as {@code api_request} wants them. Parsed descriptions are kept for ten minutes.
 */
final class OpenApiEndpoints {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHED = 100;
    static final int PAGE = 40;
    private static final List<String> METHODS = List.of("get", "head", "post", "put", "patch", "delete");

    record Endpoint(String method, String path, String summary, List<String> parameters, List<String> bodyFields, String searchText) {

        String line() {
            StringBuilder out = new StringBuilder(method).append(' ').append(path);
            if (!summary.isBlank()) {
                out.append(" - ").append(summary);
            }
            if (!parameters.isEmpty()) {
                out.append("\n    parameters: ").append(String.join(", ", parameters));
            }
            if (!bodyFields.isEmpty()) {
                out.append("\n    body: ").append(String.join(", ", bodyFields));
            }
            return out.toString();
        }
    }

    private record Cached(List<Endpoint> endpoints, Instant at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    String describe(ApiConnection connection, String query, int offset, Scope scope) {
        URI spec = connection.openApiSpec();
        if (spec == null) {
            throw new ToolInputException(connection.name() + " has no OpenAPI description configured; "
                    + "find its documentation and read it with web_fetch instead");
        }
        List<Endpoint> all = endpoints(connection, spec, scope);
        List<Endpoint> allowed = all.stream()
                .filter(e -> connection.allowedMethods().contains(e.method()))
                .filter(e -> connection.pathAllowed(e.path().replaceAll("\\{[^}/]*}", "x")))
                .toList();
        Set<String> terms = new LinkedHashSet<>();
        if (query != null) {
            Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).filter(t -> !t.isBlank()).forEach(terms::add);
        }
        List<Endpoint> matches = allowed.stream().filter(e -> terms.stream().allMatch(e.searchText()::contains)).toList();
        if (matches.isEmpty()) {
            return "No endpoints" + (terms.isEmpty() ? "" : " match '" + query.strip() + "'") + " among the "
                    + allowed.size() + " " + connection.name() + " allows.";
        }
        if (offset >= matches.size()) {
            return "Only " + matches.size() + " endpoints match; offset " + offset + " is past the end.";
        }
        int end = Math.min(matches.size(), offset + PAGE);
        StringBuilder out = new StringBuilder().append(matches.size()).append(" endpoint").append(matches.size() == 1 ? "" : "s")
                .append(terms.isEmpty() ? "" : " matching '" + query.strip() + "'")
                .append(" on ").append(connection.name());
        if (allowed.size() < all.size()) {
            out.append(" (").append(all.size() - allowed.size()).append(" more aren't allowed on this connection)");
        }
        out.append(", showing ").append(offset + 1).append('-').append(end).append(". Paths are relative to the base URL; "
                + "{name} is a path parameter; * marks required.\n");
        for (Endpoint endpoint : matches.subList(offset, end)) {
            out.append('\n').append(endpoint.line());
        }
        if (end < matches.size()) {
            out.append("\n\n[").append(matches.size() - end).append(" more: call again with offset ").append(end).append(".]");
        }
        return out.toString();
    }

    private List<Endpoint> endpoints(ApiConnection connection, URI spec, Scope scope) {
        boolean underBase = underBase(connection.baseUrl(), spec);
        String key = connection.name() + "|" + connection.baseUrl() + "|" + spec + (underBase ? "|" + scope.key() : "");
        Cached cached = cache.get(key);
        if (cached != null && cached.at().plus(TTL).isAfter(Instant.now())) {
            return cached.endpoints();
        }
        HttpResponse<byte[]> response = ApiHttp.get(connection, spec, underBase, scope, ApiTools.DESCRIBE);
        if (response.statusCode() / 100 != 2) {
            throw new ToolInputException("Couldn't read " + connection.name() + "'s OpenAPI description: HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = JSON.readTree(ApiHttp.text(response));
        } catch (JacksonException e) {
            throw new ToolInputException(connection.name() + "'s OpenAPI description isn't JSON (YAML isn't supported); "
                    + "read its documentation with web_fetch instead");
        }
        List<Endpoint> endpoints = parse(root, connection.baseUrl());
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, new Cached(endpoints, Instant.now()));
        return endpoints;
    }

    static boolean underBase(URI base, URI spec) {
        String basePath = base.getPath() == null ? "" : base.getPath();
        return spec.getScheme().equalsIgnoreCase(base.getScheme()) && spec.getHost().equalsIgnoreCase(base.getHost())
                && spec.getPort() == base.getPort() && spec.getPath() != null && spec.getPath().startsWith(basePath)
                && !spec.getPath().contains("..");
    }

    static List<Endpoint> parse(JsonNode root, URI base) {
        if (!root.path("paths").isObject()) {
            throw new ToolInputException("That OpenAPI description has no 'paths'");
        }
        String prefix = specPrefix(root);
        String basePath = base.getPath() == null ? "" : base.getPath();
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, JsonNode> pathEntry : root.path("paths").properties()) {
            String path = prefix + pathEntry.getKey();
            if (!basePath.isEmpty() && path.startsWith(basePath)) {
                path = path.substring(basePath.length());
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            JsonNode item = ref(root, pathEntry.getValue());
            for (String method : METHODS) {
                JsonNode op = item.path(method);
                if (!op.isObject()) {
                    continue;
                }
                List<String> parameters = new ArrayList<>();
                List<String> body = new ArrayList<>();
                for (JsonNode list : List.of(item.path("parameters"), op.path("parameters"))) {
                    for (JsonNode p : list) {
                        JsonNode parameter = ref(root, p);
                        String in = parameter.path("in").asString("");
                        String name = parameter.path("name").asString("");
                        if (name.isBlank() || in.equals("header") || in.equals("cookie")) {
                            continue;
                        }
                        if (in.equals("body")) {
                            body.addAll(fields(root, parameter.path("schema")));
                        } else {
                            parameters.add(name + (parameter.path("required").asBoolean(false) ? "*" : "")
                                    + " (" + (in.equals("formData") ? "form" : in) + ")");
                        }
                    }
                }
                JsonNode content = ref(root, op.path("requestBody")).path("content");
                for (String type : List.of("application/json", "application/x-www-form-urlencoded", "multipart/form-data")) {
                    if (content.has(type)) {
                        body.addAll(fields(root, content.path(type).path("schema")));
                        if (!type.equals("application/json")) {
                            body.add("(" + type + ")");
                        }
                        break;
                    }
                }
                String summary = firstLine(op.path("summary").asString(""));
                if (summary.isBlank()) {
                    summary = firstLine(op.path("description").asString(op.path("operationId").asString("")));
                }
                StringBuilder search = new StringBuilder(method).append(' ').append(path).append(' ').append(summary)
                        .append(' ').append(op.path("operationId").asString(""));
                op.path("tags").forEach(tag -> search.append(' ').append(tag.asString("")));
                endpoints.add(new Endpoint(method.toUpperCase(Locale.ROOT), path, summary, List.copyOf(parameters),
                        List.copyOf(body), search.toString().toLowerCase(Locale.ROOT)));
            }
        }
        return List.copyOf(endpoints);
    }

    /** The path the spec's own paths sit under: its first server's path (OpenAPI 3) or basePath (Swagger 2). */
    private static String specPrefix(JsonNode root) {
        String prefix = root.path("basePath").asString("");
        JsonNode server = root.path("servers").path(0).path("url");
        if (server.isString()) {
            try {
                String path = URI.create(server.asString().replaceAll("\\{[^}]*}", "x")).getPath();
                prefix = path == null ? "" : path;
            } catch (IllegalArgumentException e) {
                prefix = "";
            }
        }
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /** Top-level property names of a body schema, required ones starred. */
    private static List<String> fields(JsonNode root, JsonNode schemaRef) {
        JsonNode schema = ref(root, schemaRef);
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(r -> required.add(r.asString("")));
        List<String> fields = new ArrayList<>();
        for (String name : schema.path("properties").propertyNames()) {
            fields.add(name + (required.contains(name) ? "*" : ""));
        }
        return fields;
    }

    /** {@code node}, or what its local {@code $ref} points at (one level). */
    private static JsonNode ref(JsonNode root, JsonNode node) {
        String ref = node.path("$ref").asString("");
        if (ref.startsWith("#/")) {
            JsonNode target = root.at(ref.substring(1));
            return target.isMissingNode() ? node : target;
        }
        return node;
    }

    private static String firstLine(String text) {
        String line = text.strip().split("\\R", 2)[0].strip();
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }
}
