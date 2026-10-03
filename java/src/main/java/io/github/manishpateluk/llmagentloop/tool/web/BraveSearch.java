package io.github.manishpateluk.llmagentloop.tool.web;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link SearchProvider} over the <a href="https://brave.com/search/api/">Brave Search API</a>
 * ({@code GET /res/v1/web/search}, authenticated with an {@code X-Subscription-Token} header).
 */
public final class BraveSearch implements SearchProvider {

    static final URI DEFAULT_ENDPOINT = URI.create("https://api.search.brave.com/res/v1/web/search");

    private final Function<Scope, String> apiKey;
    private final URI endpoint;

    /** One API key for every tenant. */
    public BraveSearch(String apiKey) {
        this(scope -> apiKey);
        Objects.requireNonNull(apiKey, "apiKey");
    }

    /** An API key chosen per run, e.g. per tenant: {@code scope -> keys.braveKeyFor(scope.tenantId())}. */
    public BraveSearch(Function<Scope, String> apiKey) {
        this(apiKey, DEFAULT_ENDPOINT);
    }

    BraveSearch(Function<Scope, String> apiKey, URI endpoint) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    @Override
    public List<Result> search(String query, int count, ToolContext context) {
        URI uri = URI.create(endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20") + "&count=" + count);
        JsonNode body = SearchHttp.send(HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey.apply(context.scope()))
                .GET(), "Brave Search");

        List<Result> results = new ArrayList<>();
        for (JsonNode item : body.path("web").path("results")) {
            results.add(new Result(
                    plain(item.path("title").asString("")),
                    item.path("url").asString(""),
                    plain(item.path("description").asString(""))));
        }
        return results.stream().filter(r -> !r.url().isBlank()).limit(count).toList();
    }

    /** Brave highlights matches with {@code <strong>} tags; strip markup to plain text. */
    private static String plain(String html) {
        return Jsoup.parse(html).text();
    }
}
