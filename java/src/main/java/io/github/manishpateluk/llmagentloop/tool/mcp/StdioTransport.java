package io.github.manishpateluk.llmagentloop.tool.mcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP's stdio transport: the server is a child process; messages are newline-delimited JSON on
 * its stdin/stdout. One reader thread routes responses to their waiting requests by id, so calls
 * from concurrent runs can share the process. The server's stderr is drained and discarded.
 */
final class StdioTransport implements McpTransport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Process process;
    private final OutputStream stdin;
    private final Map<String, CompletableFuture<ObjectNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    StdioTransport(ProcessBuilder command) {
        try {
            this.process = command.redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new McpException("Couldn't start MCP server " + command.command() + ": " + e.getMessage(), e);
        }
        this.stdin = process.getOutputStream();
        Thread.ofVirtual().name("mcp-stdio-reader").start(this::readLoop);
        Thread.ofVirtual().name("mcp-stdio-stderr").start(() -> drain(process.getErrorStream()));
    }

    @Override
    public ObjectNode request(ObjectNode message, Duration timeout) {
        String id = message.get("id").asString();
        CompletableFuture<ObjectNode> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            write(message);
            return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpException("MCP server didn't answer '" + message.path("method").asString() + "' within " + timeout);
        } catch (ExecutionException e) {
            throw new McpException("MCP server connection failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for the MCP server", e);
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void notify(ObjectNode message) {
        write(message);
    }

    private synchronized void write(JsonNode message) {
        if (closed || !process.isAlive()) {
            throw new McpException("MCP server process is not running");
        }
        try {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new McpException("Couldn't write to the MCP server: " + e.getMessage(), e);
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = JSON.readTree(line);
                } catch (JacksonException e) {
                    continue; // servers occasionally log to stdout; ignore non-JSON lines
                }
                if (!(message instanceof ObjectNode object)) {
                    continue;
                }
                if (object.has("method") && object.has("id")) {
                    answerServerRequest(object);
                } else if (object.has("id") && !object.has("method")) {
                    CompletableFuture<ObjectNode> waiting = pending.get(object.get("id").asString());
                    if (waiting != null) {
                        waiting.complete(object);
                    }
                }
                // Notifications from the server (logging, progress, list_changed) are ignored.
            }
        } catch (IOException e) {
            // falls through to failing whatever is still waiting
        }
        McpException ended = new McpException("MCP server process exited" + (process.isAlive() ? "" : " (code " + exitCode() + ")"));
        pending.values().forEach(future -> future.completeExceptionally(ended));
    }

    /** We offer no client capabilities: answer {@code ping}, refuse anything else. */
    private void answerServerRequest(ObjectNode request) {
        ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
        reply.set("id", request.get("id"));
        if ("ping".equals(request.path("method").asString())) {
            reply.putObject("result");
        } else {
            reply.putObject("error").put("code", -32601).put("message", "Method not supported by this client");
        }
        try {
            write(reply);
        } catch (McpException ignored) {
            // the process is going away; the read loop will notice
        }
    }

    private int exitCode() {
        try {
            return process.waitFor(1, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void drain(InputStream stream) {
        try (stream) {
            stream.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already gone
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
