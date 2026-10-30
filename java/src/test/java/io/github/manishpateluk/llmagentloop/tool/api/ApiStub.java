package io.github.manishpateluk.llmagentloop.tool.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A local HTTP server for API tool tests: scripted routes, and a record of every request it received. */
final class ApiStub implements AutoCloseable {

    record Seen(String method, String path, String query, String auth, String contentType, String body) {
    }

    record Reply(int status, String contentType, String body) {

        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Seen, Reply>> routes = new ConcurrentHashMap<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();

    ApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port>} plus {@code path}. */
    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Answers {@code "GET /v1/contacts"}-style routes. */
    ApiStub on(String methodAndPath, Function<Seen, Reply> reply) {
        routes.put(methodAndPath, reply);
        return this;
    }

    ApiStub on(String methodAndPath, int status, String json) {
        return on(methodAndPath, seen -> Reply.json(status, json));
    }

    private void handle(HttpExchange exchange) throws IOException {
        Seen request = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        seen.add(request);
        Function<Seen, Reply> route = routes.get(request.method() + " " + request.path());
        Reply reply = route == null ? Reply.json(404, "{\"error\":\"no route\"}") : route.apply(request);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static ToolContext context(String tenant) {
        return context(tenant, "user");
    }

    static ToolContext context(String tenant, String user) {
        Scope scope = new Scope(tenant, user, null);
        return new ToolContext(UUID.randomUUID(), 0, Scope.of(tenant, user, "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }
}
