package io.github.manishpateluk.llmagentloop.tool.web;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in web tools: {@code web_fetch} (read a public page as text, or save a file into the
 * workspace) and {@code web_search} (via a {@link SearchProvider}).
 *
 * <pre>{@code
 * registry.register(WebTools.fetch())
 *         .register(WebTools.search(new BraveSearch(System.getenv("BRAVE_API_KEY"))));
 * }</pre>
 *
 * <p>{@code web_fetch} is for public pages only: no credentials are ever sent, and private,
 * loopback and cloud-metadata addresses are refused (see {@link WebFetchOptions}). For
 * authenticated APIs, use {@code api_request} with an implementor-registered connection instead.
 * An unreachable or failing site is reported back to the model, not treated as a run failure.
 */
public final class WebTools {

    public static final String FETCH = "web_fetch";
    public static final String SEARCH = "web_search";

    static final int DEFAULT_READ_CHARS = 20_000;
    static final int MAX_READ_CHARS = 100_000;
    private static final String USER_AGENT = "llm-agent-loop/1.0 (+https://github.com/manishpatelUK/llm-agent-loop)";

    private WebTools() {
    }

    public static RegisteredTool fetch() {
        return fetch(WebFetchOptions.DEFAULT);
    }

    public static RegisteredTool fetch(WebFetchOptions options) {
        Objects.requireNonNull(options, "options");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .followRedirects(HttpClient.Redirect.NEVER) // followed by hand, re-checking every hop
                .build();
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(FETCH)
                        .description("Fetch a public web page and read it as text (HTML is converted to readable "
                                + "Markdown with links). Long pages come back in pieces: use offset to continue. "
                                + "To keep a file — a PDF, CSV, image or the page itself — give save_as to store it "
                                + "in the workspace.")
                        .parameters(ToolSchemas.object(List.of("url"),
                                "url", ToolSchemas.string("The http(s) URL."),
                                "offset", ToolSchemas.integer("Character to start from. Defaults to 0."),
                                "max_chars", ToolSchemas.integer("Maximum characters to return, up to " + MAX_READ_CHARS
                                        + ". Defaults to " + DEFAULT_READ_CHARS + "."),
                                "save_as", ToolSchemas.string("Optional workspace path to save the downloaded file to, unconverted.")))
                        .build(),
                (args, context) -> fetch(client, options, args, context));
    }

    public static RegisteredTool search(SearchProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(SEARCH)
                        .description("Search the web. Returns titles, URLs and snippets; use " + FETCH
                                + " to read a result in full.")
                        .parameters(ToolSchemas.object(List.of("query"),
                                "query", ToolSchemas.string("The search query."),
                                "count", ToolSchemas.integer("Number of results, 1-10. Defaults to 5.")))
                        .build(),
                (args, context) -> {
                    String query = ToolArguments.requireString(args, "query");
                    int count = ToolArguments.optionalInt(args, "count", 5, 1, 10);
                    List<SearchProvider.Result> results = provider.search(query.strip(), count, context);
                    if (results.isEmpty()) {
                        return "No results.";
                    }
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < results.size(); i++) {
                        SearchProvider.Result r = results.get(i);
                        out.append(i + 1).append(". ").append(r.title().isBlank() ? r.url() : r.title()).append('\n')
                                .append("   ").append(r.url()).append('\n');
                        if (!r.snippet().isBlank()) {
                            out.append("   ").append(r.snippet().strip().replaceAll("\\s+", " ")).append('\n');
                        }
                    }
                    return out.toString().strip();
                });
    }

    private static String fetch(HttpClient client, WebFetchOptions options, Map<String, Object> args, ToolContext context) {
        String urlText = ToolArguments.requireString(args, "url");
        int offset = ToolArguments.optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int maxChars = ToolArguments.optionalInt(args, "max_chars", DEFAULT_READ_CHARS, 1, MAX_READ_CHARS);
        String saveAs = ToolArguments.optionalString(args, "save_as");

        Download download = download(client, options, parse(urlText.strip()));
        StringBuilder header = new StringBuilder("URL: ").append(download.uri()).append('\n')
                .append("Status: ").append(download.status()).append('\n');
        if (download.truncated()) {
            header.append("Note: the response was larger than ").append(options.maxBytes()).append(" bytes and was cut off.\n");
        }

        if (saveAs != null && !saveAs.isBlank()) {
            try {
                WorkspaceFile saved = context.workspace().write(saveAs, download.body(), download.mediaType());
                header.append("Saved ").append(saved.size()).append(" bytes (").append(saved.mediaType())
                        .append(") to ").append(saved.path()).append(".\n");
            } catch (WorkspaceException e) {
                throw new ToolInputException(e.getMessage());
            }
            if (!MediaTypes.isText(download.mediaType()) && !download.mediaType().contains("html")) {
                return header.toString().strip();
            }
        }

        String text;
        String mediaType = download.mediaType();
        if (mediaType.contains("html")) {
            HtmlText.Page page = HtmlText.convert(new String(download.body(), download.charset()), download.uri().toString());
            if (!page.title().isBlank()) {
                header.append("Title: ").append(page.title()).append('\n');
            }
            text = page.text();
        } else if (MediaTypes.isText(mediaType)) {
            text = new String(download.body(), download.charset());
        } else {
            return header.append("This is a ").append(mediaType).append(" file (").append(download.body().length)
                    .append(" bytes), which can't be shown as text. Fetch it again with save_as to keep it in the workspace.")
                    .toString();
        }

        if (offset > text.length()) {
            throw new ToolInputException("offset " + offset + " is past the end of the page (" + text.length() + " characters)");
        }
        int end = (int) Math.min(text.length(), (long) offset + maxChars);
        String slice = text.substring(offset, end);
        String more = end < text.length()
                ? "\n\n[Showing characters " + offset + "-" + end + " of " + text.length() + "; call again with offset " + end + " for more.]"
                : "";
        return header.append('\n').append(slice).append(more).toString();
    }

    private record Download(URI uri, int status, String mediaType, Charset charset, byte[] body, boolean truncated) {
    }

    private static Download download(HttpClient client, WebFetchOptions options, URI start) {
        URI uri = start;
        for (int hop = 0; ; hop++) {
            NetworkGuard.check(uri, options);
            HttpResponse<InputStream> response;
            try {
                response = client.send(HttpRequest.newBuilder(uri)
                                .timeout(options.timeout())
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new ToolInputException("Timed out fetching " + uri);
            } catch (IOException e) {
                throw new ToolInputException("Couldn't fetch " + uri + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while fetching " + uri, e);
            } catch (IllegalArgumentException e) {
                throw new ToolInputException("Invalid URL: " + uri);
            }

            int status = response.statusCode();
            if (status / 100 == 3 && response.headers().firstValue("Location").isPresent()) {
                closeQuietly(response.body());
                if (hop >= options.maxRedirects()) {
                    throw new ToolInputException("Too many redirects starting from " + start);
                }
                uri = uri.resolve(response.headers().firstValue("Location").get().strip());
                continue;
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String mediaType = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
            if (mediaType.isEmpty()) {
                mediaType = MediaTypes.guess(uri.getPath() == null ? "" : uri.getPath(), MediaTypes.OCTET_STREAM);
            }
            byte[] body;
            boolean truncated;
            try (InputStream in = response.body()) {
                body = in.readNBytes(options.maxBytes() + 1);
                truncated = body.length > options.maxBytes();
                if (truncated) {
                    body = Arrays.copyOf(body, options.maxBytes());
                }
            } catch (IOException e) {
                throw new ToolInputException("Connection dropped while reading " + uri);
            }
            return new Download(uri, status, mediaType, charset(contentType), body, truncated);
        }
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null) {
                uri = new URI("https://" + url);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ToolInputException("Invalid URL: " + url);
        }
    }

    private static Charset charset(String contentType) {
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

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
