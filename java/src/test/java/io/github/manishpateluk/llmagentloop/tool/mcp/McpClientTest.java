package io.github.manishpateluk.llmagentloop.tool.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void stdioServerToolsBecomeAgentToolsAcrossPages() {
        try (McpClient client = fakeStdioServer()) {
            List<RegisteredTool> tools = client.tools();

            assertThat(tools).extracting(t -> t.definition().getName())
                    .containsExactly("fake_echo", "fake_fail", "fake_weird_name_v2");
            RegisteredTool echo = tools.getFirst();
            assertThat(echo.definition().getDescription()).isEqualTo("Echo text back");
            assertThat(echo.definition().getParameters()).containsEntry("type", "object").containsKey("properties");
            assertThat(tools.get(1).definition().getParameters()).containsEntry("type", "object");
            assertThat(tools.get(2).definition().getDescription()).contains("weird.name/v2", "fake");

            assertThat(echo.handler().handle(Map.of("text", "hello"), null)).isEqualTo("echo: hello");
            assertThat(tools.get(2).handler().handle(Map.of(), null)).isEqualTo("{\"ok\":true}");
        }
    }

    @Test
    void toolFailuresAndUnknownToolsAreFixableErrors() {
        try (McpClient client = fakeStdioServer()) {
            assertThatThrownBy(() -> client.callTool("fail", Map.of()))
                    .isInstanceOf(ToolInputException.class).hasMessage("it broke");
            assertThatThrownBy(() -> client.callTool("nope", Map.of()))
                    .isInstanceOf(ToolInputException.class).hasMessageContaining("Unknown tool");
        }
    }

    @Test
    void concurrentCallsShareOneProcess() throws InterruptedException {
        try (McpClient client = fakeStdioServer()) {
            List<String> results = new CopyOnWriteArrayList<>();
            List<Thread> threads = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                threads.add(Thread.ofVirtual().start(() -> results.add(client.callTool("echo", Map.of("text", "m" + n)))));
            }
            for (Thread thread : threads) {
                thread.join();
            }
            assertThat(results).hasSize(20).contains("echo: m0", "echo: m19");
        }
    }

    @Test
    void aServerThatCannotStartIsAnInfrastructureFailure() {
        assertThatThrownBy(() -> McpClient.stdio("missing", List.of("definitely-not-a-real-command-xyz")))
                .isInstanceOf(McpException.class);
    }

    @Test
    void streamableHttpEchoesTheSessionAndReadsEventStreamResponses() throws IOException {
        List<Map<String, String>> seenHeaders = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcp", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            JsonNode message = JSON.readTree(exchange.getRequestBody().readAllBytes());
            seenHeaders.add(Map.of(
                    "session", String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")),
                    "version", String.valueOf(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")),
                    "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
            String method = message.path("method").asString();
            String id = message.path("id").asString("");
            switch (method) {
                case "initialize" -> {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-42");
                    json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"protocolVersion\":\"2025-03-26\","
                            + "\"capabilities\":{},\"serverInfo\":{\"name\":\"remote\"}}}");
                }
                case "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                }
                case "tools/list" -> json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"tools\":["
                        + "{\"name\":\"search\",\"description\":\"Search docs\",\"inputSchema\":{\"type\":\"object\"}}]}}");
                case "tools/call" -> sse(exchange,
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":"
                                + "{\"content\":[{\"type\":\"text\",\"text\":\"found 3 docs\"}]}}\n\n");
                default -> json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"error\":{\"code\":-32601,\"message\":\"no\"}}");
            }
        });
        server.start();
        try (McpClient client = McpClient.http("docs", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                Map.of("Authorization", "Bearer t"))) {
            List<RegisteredTool> tools = client.tools();

            assertThat(tools).extracting(t -> t.definition().getName()).containsExactly("docs_search");
            assertThat(tools.getFirst().handler().handle(Map.of("q", "x"), null)).isEqualTo("found 3 docs");
        } finally {
            server.stop(0);
        }

        assertThat(seenHeaders.getFirst()).containsEntry("session", "null").containsEntry("auth", "Bearer t");
        assertThat(seenHeaders.subList(1, seenHeaders.size()))
                .allSatisfy(h -> assertThat(h).containsEntry("session", "sess-42").containsEntry("version", "2025-03-26"));
    }

    @Test
    void uniqueNamesAreProviderSafeTruncatedAndDeduplicated() {
        Set<String> used = new HashSet<>();

        assertThat(McpClient.uniqueName("srv_a.b c", used)).isEqualTo("srv_a_b_c");
        assertThat(McpClient.uniqueName("srv_a/b c", used)).isEqualTo("srv_a_b_c_2");
        assertThat(McpClient.uniqueName("x".repeat(80), used)).hasSize(64);
        assertThat(McpClient.uniqueName("x".repeat(80), used)).hasSize(64).endsWith("_2");
    }

    private static McpClient fakeStdioServer() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName());
        return McpClient.stdio("fake", process, Duration.ofSeconds(30));
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        respond(exchange, "application/json", body);
    }

    private static void sse(HttpExchange exchange, String body) throws IOException {
        respond(exchange, "text/event-stream", body);
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
