package io.github.manishpateluk.llmagentloop.tool.web;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link SearchProvider} over the <a href="https://tavily.com">Tavily</a> search API
 * ({@code POST /search} with a JSON body, authenticated with a bearer token) — built for LLM
 * agents, so its snippets are already extracted page content.
 */
public final class TavilySearch implements SearchProvider {

    static final URI DEFAULT_ENDPOINT = URI.create("https://api.tavily.com/search");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Function<Scope, String> apiKey;
    private final URI endpoint;

    /** One API key for every tenant. */
    public TavilySearch(String apiKey) {
        this(scope -> apiKey);
        Objects.requireNonNull(apiKey, "apiKey");
    }

    /** An API key chosen per run, e.g. per tenant. */
    public TavilySearch(Function<Scope, String> apiKey) {
        this(apiKey, DEFAULT_ENDPOINT);
    }

    TavilySearch(Function<Scope, String> apiKey, URI endpoint) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    @Override
    public List<Result> search(String query, int count, ToolContext context) {
        String requestBody = JSON.writeValueAsString(Map.of("query", query, "max_results", count));
        JsonNode body = SearchHttp.send(HttpRequest.newBuilder(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.apply(context.scope()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)), "Tavily");

        List<Result> results = new ArrayList<>();
        for (JsonNode item : body.path("results")) {
            results.add(new Result(
                    item.path("title").asString(""),
                    item.path("url").asString(""),
                    item.path("content").asString("")));
        }
        return results.stream().filter(r -> !r.url().isBlank()).limit(count).toList();
    }
}
