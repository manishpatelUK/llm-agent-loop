package io.github.manishpateluk.llmagentloop.tool.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebToolsTest {

    private static final WebFetchOptions LOCAL = WebFetchOptions.DEFAULT.withAllowPrivateNetworks(true);

    private HttpServer server;
    private String base;
    private ToolContext context;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", exchange -> respond(exchange, 200, "text/html; charset=utf-8", """
                <html><head><title>Pricing</title><script>var tracking = 1;</script></head>
                <body><nav>Home | About</nav>
                <main><h1>Plans</h1><p>Our <strong>Pro</strong> plan costs £49.</p>
                <ul><li>Unlimited users</li><li>Priority support</li></ul>
                <p>See <a href="/terms">the terms</a>.</p>
                <table><tr><th>Plan</th><th>Price</th></tr><tr><td>Pro</td><td>£49</td></tr></table></main>
                <footer>© Acme</footer></body></html>
                """));
        server.createContext("/data.json", exchange -> respond(exchange, 200, "application/json", "{\"ok\":true}"));
        server.createContext("/long.txt", exchange -> respond(exchange, 200, "text/plain", "abcdefghij"));
        server.createContext("/report.pdf", exchange -> respond(exchange, 200, "application/pdf", "%PDF-1.7 fake"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "text/plain", "not here"));
        server.createContext("/hop", exchange -> redirect(exchange, "/page"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.createContext("/to-file", exchange -> redirect(exchange, "file:///etc/passwd"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Scope scope = new Scope("acme", "alice", null);
        context = new ToolContext(UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void htmlBecomesReadableTextPreferringMainContent() {
        String result = fetch(LOCAL, Map.of("url", base + "/page"));

        assertThat(result).contains("Status: 200", "Title: Pricing", "# Plans", "Our **Pro** plan costs £49.",
                "- Unlimited users", "[the terms](" + base + "/terms)", "| Plan | Price |", "| Pro | £49 |");
        assertThat(result).doesNotContain("tracking", "Home | About", "© Acme", "<p>");
    }

    @Test
    void textAndJsonComeBackAsIsAndLongContentIsPaged() {
        assertThat(fetch(LOCAL, Map.of("url", base + "/data.json"))).endsWith("{\"ok\":true}");
        assertThat(fetch(LOCAL, Map.of("url", base + "/long.txt", "max_chars", 4))).contains("abcd").contains("offset 4");
        assertThat(fetch(LOCAL, Map.of("url", base + "/long.txt", "offset", 4))).endsWith("efghij");
    }

    @Test
    void binaryFilesAreDescribedOrSavedToTheWorkspace() {
        assertThat(fetch(LOCAL, Map.of("url", base + "/report.pdf"))).contains("application/pdf", "save_as");

        String saved = fetch(LOCAL, Map.of("url", base + "/report.pdf", "save_as", "downloads/report.pdf"));
        assertThat(saved).contains("Saved 13 bytes (application/pdf) to downloads/report.pdf");
        assertThat(context.workspace().require("downloads/report.pdf").mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void errorStatusesAreReportedToTheModelNotThrown() {
        assertThat(fetch(LOCAL, Map.of("url", base + "/missing"))).contains("Status: 404", "not here");
    }

    @Test
    void redirectsAreFollowedAndEachHopIsRechecked() {
        assertThat(fetch(LOCAL, Map.of("url", base + "/hop"))).contains("URL: " + base + "/page", "# Plans");
        assertThatThrownBy(() -> fetch(LOCAL, Map.of("url", base + "/loop")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Too many redirects");
        assertThatThrownBy(() -> fetch(LOCAL, Map.of("url", base + "/to-file")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Only http and https");
    }

    @Test
    void oversizedResponsesAreCutOff() {
        WebFetchOptions tiny = new WebFetchOptions(List.of(), List.of(), true, Duration.ofSeconds(5), 4, 5);

        assertThat(fetch(tiny, Map.of("url", base + "/long.txt"))).contains("cut off").endsWith("abcd");
    }

    @Test
    void byDefaultLocalAndPrivateAddressesAreRefused() {
        assertThatThrownBy(() -> fetch(WebFetchOptions.DEFAULT, Map.of("url", base + "/page")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("private or local");
        assertThatThrownBy(() -> fetch(WebFetchOptions.DEFAULT, Map.of("url", "http://localhost:1/")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("private or local");
        assertThatThrownBy(() -> fetch(WebFetchOptions.DEFAULT, Map.of("url", "http://169.254.169.254/latest/meta-data/")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("private or local");
    }

    @Test
    void schemesCredentialsAndDomainListsAreEnforced() {
        assertThatThrownBy(() -> fetch(LOCAL, Map.of("url", "ftp://example.com/x")))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> fetch(LOCAL, Map.of("url", "http://user:pass@127.0.0.1/")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("credentials");
        assertThatThrownBy(() -> fetch(LOCAL.withAllowedDomains(List.of("example.com")), Map.of("url", base + "/page")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("isn't allowed");
        assertThatThrownBy(() -> fetch(LOCAL.withBlockedDomains(List.of("127.0.0.1")), Map.of("url", base + "/page")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("isn't allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1"})
    void privateAndSpecialAddressesAreRecognised(String address) throws IOException {
        assertThat(NetworkGuard.isPrivate(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:4700::1111", "::ffff:8.8.8.8"})
    void publicAddressesAreAllowed(String address) throws IOException {
        assertThat(NetworkGuard.isPrivate(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void braveSearchSendsTheKeyAndStripsHighlightMarkup() {
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/brave", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            assertThat(exchange.getRequestURI().getQuery()).contains("q=llm agents", "count=2");
            respond(exchange, 200, "application/json", """
                    {"web":{"results":[
                      {"title":"Agents <strong>101</strong>","url":"https://a.example","description":"All about <strong>agents</strong>"},
                      {"title":"Second","url":"https://b.example","description":""}]}}
                    """);
        });
        SearchProvider brave = new BraveSearch(scope -> "key-for-" + scope.tenantId(), URI.create(base + "/brave"));

        String result = search(brave, Map.of("query", "llm agents", "count", 2));

        assertThat(token.get()).isEqualTo("key-for-acme");
        assertThat(result).isEqualTo("""
                1. Agents 101
                   https://a.example
                   All about agents
                2. Second
                   https://b.example""");
    }

    @Test
    void tavilySearchPostsJsonWithABearerToken() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/tavily", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json",
                    "{\"results\":[{\"title\":\"T\",\"url\":\"https://t.example\",\"content\":\"extracted text\"}]}");
        });

        String result = search(new TavilySearch(scope -> "tvly-key", URI.create(base + "/tavily")), Map.of("query", "q"));

        assertThat(auth.get()).isEqualTo("Bearer tvly-key");
        assertThat(body.get()).contains("\"query\":\"q\"", "\"max_results\":5");
        assertThat(result).contains("https://t.example", "extracted text");
    }

    @Test
    void rateLimitingIsFixableButABadKeyEndsTheRun() {
        server.createContext("/limited", exchange -> respond(exchange, 429, "application/json", "{}"));
        server.createContext("/unauthorized", exchange -> respond(exchange, 401, "application/json", "{\"error\":\"bad key\"}"));

        assertThatThrownBy(() -> search(new BraveSearch(s -> "k", URI.create(base + "/limited")), Map.of("query", "q")))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> search(new BraveSearch(s -> "k", URI.create(base + "/unauthorized")), Map.of("query", "q")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("401");
    }

    private String fetch(WebFetchOptions options, Map<String, Object> args) {
        return call(WebTools.fetch(options), args);
    }

    private String search(SearchProvider provider, Map<String, Object> args) {
        return call(WebTools.search(provider), args);
    }

    private String call(RegisteredTool tool, Map<String, Object> args) {
        return tool.handler().handle(args, context);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
