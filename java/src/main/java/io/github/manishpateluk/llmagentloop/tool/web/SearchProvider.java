package io.github.manishpateluk.llmagentloop.tool.web;

import io.github.manishpateluk.llmagentloop.tool.ToolContext;

import java.util.List;
import java.util.Objects;

/**
 * A web search backend for {@code web_search}. {@link BraveSearch} and {@link TavilySearch} are
 * provided; implement this for any other (Bing, SerpAPI, an internal index). Implementations
 * should throw {@link io.github.manishpateluk.llmagentloop.tool.ToolInputException} for problems
 * the model can act on (e.g. rate limiting) and anything else for misconfiguration (e.g. a bad key).
 */
@FunctionalInterface
public interface SearchProvider {

    /** Up to {@code count} results for {@code query}; {@code context.scope()} can select per-tenant credentials. */
    List<Result> search(String query, int count, ToolContext context);

    /**
     * @param title   the page title
     * @param url     the page address
     * @param snippet a short plain-text extract; may be empty
     */
    record Result(String title, String url, String snippet) {
        public Result {
            Objects.requireNonNull(url, "url");
            title = title == null ? "" : title;
            snippet = snippet == null ? "" : snippet;
        }
    }
}
