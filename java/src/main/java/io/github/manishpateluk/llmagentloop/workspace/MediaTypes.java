package io.github.manishpateluk.llmagentloop.workspace;

import java.util.Locale;
import java.util.Map;

/** Media type guessing from file extensions, and which types count as text. */
public final class MediaTypes {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("xml", "application/xml"),
            Map.entry("json", "application/json"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("js", "text/javascript"),
            Map.entry("py", "text/x-python"),
            Map.entry("java", "text/x-java"),
            Map.entry("sql", "application/sql"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("xlsx", XLSX),
            Map.entry("docx", DOCX),
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"));

    private MediaTypes() {
    }

    /** Guessed from {@code path}'s extension; {@code fallback} when unknown. */
    public static String guess(String path, String fallback) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1 || path.indexOf('/', dot) >= 0) {
            return fallback;
        }
        return BY_EXTENSION.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT), fallback);
    }

    public static boolean isText(String mediaType) {
        String type = mediaType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.endsWith("+xml")
                || type.endsWith("+json")
                || type.equals("application/json")
                || type.equals("application/xml")
                || type.equals("application/yaml")
                || type.equals("application/sql");
    }
}
