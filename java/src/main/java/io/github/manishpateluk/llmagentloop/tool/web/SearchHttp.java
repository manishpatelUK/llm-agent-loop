package io.github.manishpateluk.llmagentloop.tool.web;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The HTTP round trip shared by the bundled {@link SearchProvider}s. */
final class SearchHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SearchHttp() {
    }

    /**
     * Sends the request and parses its JSON body. A 429 is something the model can wait out, so
     * it's a {@link ToolInputException}; any other failure (bad key, outage) is a configuration or
     * infrastructure problem and ends the run.
     */
    static JsonNode send(HttpRequest.Builder request, String provider) {
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(provider + " request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(provider + " request was interrupted", e);
        }
        if (response.statusCode() == 429) {
            throw new ToolInputException(provider + " is rate limiting searches right now; try again shortly or continue without searching");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(provider + " returned HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        try {
            return JSON.readTree(response.body());
        } catch (JacksonException e) {
            throw new IllegalStateException(provider + " returned a response that isn't JSON", e);
        }
    }

    private static String abbreviate(String body) {
        return body == null ? "" : body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
