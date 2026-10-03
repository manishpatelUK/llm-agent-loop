package io.github.manishpateluk.llmagentloop.tool.mcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * MCP's Streamable HTTP transport: every message is a POST to one endpoint; a request's response
 * comes back either as a JSON body or as a server-sent-event stream that eventually carries it.
 * The {@code Mcp-Session-Id} the server assigns at initialization is echoed on every later request.
 */
final class HttpTransport implements McpTransport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile String sessionId;
    private volatile String protocolVersion;

    HttpTransport(URI endpoint, Map<String, String> headers) {
        this.endpoint = endpoint;
        this.headers = Map.copyOf(headers);
    }

    @Override
    public void protocolVersion(String version) {
        this.protocolVersion = version;
    }

    @Override
    public ObjectNode request(ObjectNode message, Duration timeout) {
        HttpResponse<InputStream> response = post(message, timeout);
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
        String id = message.get("id").asString();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new McpException("MCP server returned HTTP " + response.statusCode() + " for '"
                        + message.path("method").asString() + "'");
            }
            if (contentType.startsWith("text/event-stream")) {
                return fromEventStream(body, id);
            }
            JsonNode parsed = JSON.readTree(body);
            if (parsed instanceof ObjectNode object && id.equals(object.path("id").asString())) {
                return object;
            }
            if (parsed.isArray()) {
                for (JsonNode item : parsed) {
                    if (item instanceof ObjectNode object && id.equals(object.path("id").asString())) {
                        return object;
                    }
                }
            }
            throw new McpException("MCP server's response didn't answer request " + id);
        } catch (IOException | JacksonException e) {
            throw new McpException("Couldn't read the MCP server's response: " + e.getMessage(), e);
        }
    }

    @Override
    public void notify(ObjectNode message) {
        HttpResponse<InputStream> response = post(message, Duration.ofSeconds(30));
        try (InputStream body = response.body()) {
            body.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // a notification has no answer to wait for
        }
        if (response.statusCode() / 100 != 2) {
            throw new McpException("MCP server returned HTTP " + response.statusCode() + " for notification '"
                    + message.path("method").asString() + "'");
        }
    }

    private HttpResponse<InputStream> post(ObjectNode message, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(message)));
        headers.forEach(request::header);
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            request.header("MCP-Protocol-Version", protocolVersion);
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new McpException("Couldn't reach MCP server at " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted calling the MCP server", e);
        }
    }

    /** Reads SSE events until one carries the JSON-RPC response with {@code id}; other events are skipped. */
    private static ObjectNode fromEventStream(InputStream body, String id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                ObjectNode found = parseEvent(data.toString(), id);
                if (found != null) {
                    return found;
                }
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading()).append('\n');
            }
        }
        ObjectNode last = parseEvent(data.toString(), id);
        if (last != null) {
            return last;
        }
        throw new McpException("MCP server's event stream ended without answering request " + id);
    }

    private static ObjectNode parseEvent(String data, String id) {
        if (data.isBlank()) {
            return null;
        }
        try {
            JsonNode message = JSON.readTree(data);
            if (message instanceof ObjectNode object && !object.has("method") && id.equals(object.path("id").asString())) {
                return object;
            }
        } catch (JacksonException ignored) {
            // not a JSON-RPC message
        }
        return null;
    }

    @Override
    public void close() {
        if (sessionId == null) {
            return;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).DELETE()
                .header("Mcp-Session-Id", sessionId);
        headers.forEach(request::header);
        try {
            client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException ignored) {
            // best effort: the server will expire the session
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
