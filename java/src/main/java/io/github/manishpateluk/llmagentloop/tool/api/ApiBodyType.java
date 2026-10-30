package io.github.manishpateluk.llmagentloop.tool.api;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How a request body is encoded. Most APIs take JSON; some (Stripe, many older APIs) take form
 * fields, which the model gives as name/value pairs rather than raw text. Set a connection's
 * default with {@link ApiConnection.Builder#bodyType}; the model can override it per call.
 */
public enum ApiBodyType {

    /** {@code application/json}: the body must parse as JSON. */
    JSON("application/json"),
    /** {@code application/x-www-form-urlencoded}: built from name/value pairs. */
    FORM("application/x-www-form-urlencoded"),
    /** {@code text/plain}. */
    TEXT("text/plain");

    private final String mediaType;

    ApiBodyType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Every media type, as offered to the model. */
    static List<String> mediaTypes() {
        return Arrays.stream(values()).map(ApiBodyType::mediaType).toList();
    }

    /** The type with this media type (ignoring case and parameters such as a charset), if any. */
    public static Optional<ApiBodyType> ofMediaType(String mediaType) {
        if (mediaType == null) {
            return Optional.empty();
        }
        String bare = mediaType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.mediaType.equals(bare)).findFirst();
    }
}
