package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

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
import java.util.TreeSet;

/**
 * Sending requests through an {@link ApiConnection}, shared by {@code api_request},
 * {@code procedure_run} and {@code api_describe} so they all follow the same rules: only the
 * connection's methods, only paths under its base URL that it allows, its headers and credentials
 * added, redirects not followed, and one retry after a 401 when the {@link ApiAuth} renews.
 */
final class ApiHttp {

    static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static final String ACCEPT = "application/json, text/plain;q=0.9, */*;q=0.8";

    /** An encoded request body. */
    record Body(ApiBodyType type, String text) {
    }

    private ApiHttp() {
    }

    /**
     * Sends {@code method path} with {@code query} (to which the auth's query parameters are added)
     * and {@code body} (or {@code null}). {@code caller} names who's asking, for failure messages.
     *
     * @throws ToolInputException    if the method or path isn't allowed on the connection
     * @throws UncheckedIOException  if the API can't be reached at all
     */
    static HttpResponse<byte[]> send(ApiConnection connection, String method, String path, Map<String, String> query,
                                     Body body, Scope scope, String caller) {
        checkMethod(connection, method);
        if (body != null && ApiConnection.READ_METHODS.contains(method)) {
            throw new ToolInputException(method + " requests can't have a body");
        }
        URI uri = resolve(connection, path, withAuth(connection, query, scope));
        HttpResponse<byte[]> response = exchange(connection, method, uri, body, scope, caller);
        if (response.statusCode() == 401 && connection.auth().renewAfterUnauthorized(scope)) {
            response = exchange(connection, method, resolve(connection, path, withAuth(connection, query, scope)), body, scope, caller);
        }
        return response;
    }

    static void checkMethod(ApiConnection connection, String method) {
        if (!connection.allowedMethods().contains(method)) {
            throw new ToolInputException(method + " isn't allowed on " + connection.name() + "; allowed: "
                    + new TreeSet<>(connection.allowedMethods()));
        }
    }

    /** Fetches an absolute {@code uri} with GET, with the connection's credentials only if {@code authenticate}. */
    static HttpResponse<byte[]> get(ApiConnection connection, URI uri, boolean authenticate, Scope scope, String caller) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(connection.timeout()).header("Accept", ACCEPT).GET();
        if (authenticate) {
            headers(connection, scope).forEach(request::setHeader);
        }
        return send(request.build(), connection, caller);
    }

    private static Map<String, String> withAuth(ApiConnection connection, Map<String, String> query, Scope scope) {
        Map<String, String> all = new LinkedHashMap<>(query);
        all.putAll(connection.auth().queryParameters(scope));
        return all;
    }

    private static Map<String, String> headers(ApiConnection connection, Scope scope) {
        Map<String, String> headers = new LinkedHashMap<>(connection.defaultHeaders());
        headers.putAll(connection.auth().headers(scope));
        return headers;
    }

    private static HttpResponse<byte[]> exchange(ApiConnection connection, String method, URI uri, Body body, Scope scope,
                                                 String caller) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(connection.timeout())
                .header("Accept", ACCEPT)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.text()));
        if (body != null) {
            request.header("Content-Type", body.type().mediaType() + "; charset=utf-8");
        }
        headers(connection, scope).forEach(request::setHeader);
        return send(request.build(), connection, caller);
    }

    private static HttpResponse<byte[]> send(HttpRequest request, ApiConnection connection, String caller) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(caller + " to connection '" + connection.name() + "' failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(caller + " to connection '" + connection.name() + "' was interrupted", e);
        }
    }

    /**
     * The connection's base URL plus a path, refusing anything that could leave it: a different
     * host, {@code ..} segments (encoded or not), backslashes, or an embedded query or fragment.
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

    /** {@code application/x-www-form-urlencoded} text from name/value pairs, in order. */
    static String formEncode(List<Map.Entry<String, String>> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> field : fields) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(field.getValue() == null ? "" : field.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Percent-encodes for a query string or a single path segment. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";")[0].strip().toLowerCase(Locale.ROOT);
    }

    static Charset charset(String contentType) {
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

    /** The response body as text, in its declared charset. */
    static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), charset(response.headers().firstValue("Content-Type").orElse("")));
    }
}
