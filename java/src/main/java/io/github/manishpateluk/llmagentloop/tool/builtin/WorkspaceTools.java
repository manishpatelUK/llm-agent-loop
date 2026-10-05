package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolHandler;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceException;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFileInfo;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in workspace file tools — {@code workspace_list}, {@code workspace_read},
 * {@code workspace_write}, {@code workspace_edit}, {@code workspace_delete},
 * {@code workspace_search} — over the run's {@code Workspace}, scoped to the current user (or
 * whatever level the loop is configured with). Register with
 * {@code registry.registerAll(WorkspaceTools.all())}; they need
 * {@code AgentLoop.builder().workspace(...)} set, e.g. to an {@code InMemoryWorkspace}.
 *
 * <p>Path safety and size limits are enforced by {@code ScopedWorkspace}; its refusals, like any
 * other fixable problem, come back to the model as an error result rather than ending the run.
 */
public final class WorkspaceTools {

    public static final String LIST = "workspace_list";
    public static final String READ = "workspace_read";
    public static final String WRITE = "workspace_write";
    public static final String EDIT = "workspace_edit";
    public static final String DELETE = "workspace_delete";
    public static final String SEARCH = "workspace_search";
    public static final String VIEW = "workspace_view";

    /** Largest file {@code workspace_view} will show the model. */
    static final int MAX_VIEW_BYTES = 20 * 1024 * 1024;

    /** Default and maximum characters {@code workspace_read} returns per call, so one big file can't flood the context window. */
    static final int DEFAULT_READ_CHARS = 20_000;
    static final int MAX_READ_CHARS = 100_000;
    static final int MAX_SEARCH_MATCHES = 100;
    static final int MAX_MATCH_LINE_CHARS = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WorkspaceTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(list(), read(), write(), edit(), delete(), search(), view());
    }

    public static RegisteredTool list() {
        return tool(LIST,
                "List files in the workspace — this user's persistent file area for drafts, reports and other documents.",
                ToolSchemas.object(List.of(),
                        "prefix", ToolSchemas.string("Only list paths starting with this, e.g. \"reports/\". Omit for all files.")),
                (args, context) -> {
                    List<WorkspaceFileInfo> files = context.workspace().list(ToolArguments.optionalString(args, "prefix"));
                    if (files.isEmpty()) {
                        return "No files.";
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (WorkspaceFileInfo info : files) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", info.path());
                        row.put("mediaType", info.mediaType());
                        row.put("bytes", info.size());
                        row.put("modifiedAt", info.modifiedAt().toString());
                        rows.add(row);
                    }
                    return JSON.writeValueAsString(rows);
                });
    }

    public static RegisteredTool read() {
        return tool(READ,
                "Read a text file from the workspace. Long files come back in pieces: use offset to continue.",
                ToolSchemas.object(List.of("path"),
                        "path", ToolSchemas.string("File path, e.g. \"notes/plan.md\"."),
                        "offset", ToolSchemas.integer("Character to start from. Defaults to 0."),
                        "max_chars", ToolSchemas.integer("Maximum characters to return, up to " + MAX_READ_CHARS
                                + ". Defaults to " + DEFAULT_READ_CHARS + ".")),
                (args, context) -> {
                    WorkspaceFile file = context.workspace().require(ToolArguments.requireString(args, "path"));
                    if (!file.isText()) {
                        return file.path() + " is a binary file (" + file.mediaType() + ", " + file.size()
                                + " bytes) and can't be shown as text.";
                    }
                    String text = file.text();
                    int offset = ToolArguments.optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
                    int maxChars = ToolArguments.optionalInt(args, "max_chars", DEFAULT_READ_CHARS, 1, MAX_READ_CHARS);
                    if (offset > text.length()) {
                        throw new ToolInputException("offset " + offset + " is past the end of " + file.path()
                                + " (" + text.length() + " characters)");
                    }
                    int end = (int) Math.min(text.length(), (long) offset + maxChars);
                    String slice = text.substring(offset, end);
                    return end < text.length()
                            ? slice + "\n\n[Showing characters " + offset + "-" + end + " of " + text.length()
                                    + "; call again with offset " + end + " for more.]"
                            : slice;
                });
    }

    public static RegisteredTool write() {
        return tool(WRITE,
                "Create a text file in the workspace, or replace it entirely if it exists. To change part of an "
                        + "existing file, use " + EDIT + " instead.",
                ToolSchemas.object(List.of("path", "content"),
                        "path", ToolSchemas.string("File path, e.g. \"drafts/launch-email.md\". Folders are created as needed."),
                        "content", ToolSchemas.string("The complete file content.")),
                (args, context) -> {
                    WorkspaceFile file = context.workspace().writeText(
                            ToolArguments.requireString(args, "path"), ToolArguments.requireStringAllowEmpty(args, "content"));
                    return "Wrote " + file.size() + " bytes to " + file.path() + ".";
                });
    }

    public static RegisteredTool edit() {
        return tool(EDIT,
                "Replace exact text in a workspace text file. old_text must match the file exactly, including "
                        + "whitespace, and be unique unless replace_all is true.",
                ToolSchemas.object(List.of("path", "old_text", "new_text"),
                        "path", ToolSchemas.string("File path."),
                        "old_text", ToolSchemas.string("The exact text to replace. Include enough surrounding text to make it unique."),
                        "new_text", ToolSchemas.string("The replacement text."),
                        "replace_all", ToolSchemas.bool("Replace every occurrence instead of requiring exactly one. Defaults to false.")),
                (args, context) -> {
                    WorkspaceFile file = context.workspace().require(ToolArguments.requireString(args, "path"));
                    if (!file.isText()) {
                        throw new ToolInputException(file.path() + " is a binary file and can't be edited as text");
                    }
                    String oldText = ToolArguments.requireStringAllowEmpty(args, "old_text");
                    String newText = ToolArguments.requireStringAllowEmpty(args, "new_text");
                    boolean replaceAll = ToolArguments.optionalBoolean(args, "replace_all", false);
                    if (oldText.isEmpty()) {
                        throw new ToolInputException("old_text must not be empty");
                    }

                    String text = file.text();
                    int occurrences = countOccurrences(text, oldText);
                    if (occurrences == 0) {
                        throw new ToolInputException("old_text was not found in " + file.path());
                    }
                    if (occurrences > 1 && !replaceAll) {
                        throw new ToolInputException("old_text appears " + occurrences + " times in " + file.path()
                                + "; include more surrounding text to make it unique, or set replace_all");
                    }
                    String updated = replaceAll ? text.replace(oldText, newText) : replaceFirst(text, oldText, newText);
                    context.workspace().write(file.path(), updated.getBytes(StandardCharsets.UTF_8),
                            file.mediaType());
                    return "Replaced " + (replaceAll ? occurrences : 1) + " occurrence(s) in " + file.path() + ".";
                });
    }

    public static RegisteredTool delete() {
        return tool(DELETE,
                "Delete a file from the workspace.",
                ToolSchemas.object(List.of("path"),
                        "path", ToolSchemas.string("File path.")),
                (args, context) -> {
                    String path = ToolArguments.requireString(args, "path");
                    if (!context.workspace().delete(path)) {
                        throw new WorkspaceException("No such file: " + path);
                    }
                    return "Deleted " + path + ".";
                });
    }

    public static RegisteredTool search() {
        return tool(SEARCH,
                "Find lines containing some text across the workspace's text files (case-insensitive). "
                        + "Returns path:line: text for each match.",
                ToolSchemas.object(List.of("query"),
                        "query", ToolSchemas.string("Text to look for."),
                        "prefix", ToolSchemas.string("Only search paths starting with this. Omit for all files.")),
                (args, context) -> {
                    String needle = ToolArguments.requireString(args, "query").toLowerCase(Locale.ROOT);
                    List<String> matches = new ArrayList<>();
                    boolean truncated = false;
                    for (WorkspaceFileInfo info : context.workspace().list(ToolArguments.optionalString(args, "prefix"))) {
                        WorkspaceFile file = context.workspace().read(info.path()).orElse(null);
                        if (file == null || !file.isText()) {
                            continue;
                        }
                        String[] lines = file.text().split("\\R", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                                if (matches.size() == MAX_SEARCH_MATCHES) {
                                    truncated = true;
                                    break;
                                }
                                String line = lines[i].strip();
                                if (line.length() > MAX_MATCH_LINE_CHARS) {
                                    line = line.substring(0, MAX_MATCH_LINE_CHARS) + "...";
                                }
                                matches.add(file.path() + ":" + (i + 1) + ": " + line);
                            }
                        }
                        if (truncated) {
                            break;
                        }
                    }
                    if (matches.isEmpty()) {
                        return "No matches.";
                    }
                    String result = String.join("\n", matches);
                    return truncated ? result + "\n[Stopped at " + MAX_SEARCH_MATCHES + " matches; narrow the query or prefix.]" : result;
                });
    }

    public static RegisteredTool view() {
        return tool(VIEW,
                "Look at an image or PDF in the workspace yourself — a photo, screenshot, chart, or scanned "
                        + "document — rather than reading extracted text. It becomes visible to you from your next "
                        + "step on (if the current model supports images/files).",
                ToolSchemas.object(List.of("path"),
                        "path", ToolSchemas.string("Workspace path of an image (PNG, JPEG, GIF, WebP) or PDF.")),
                (args, context) -> {
                    WorkspaceFile file = context.workspace().require(ToolArguments.requireString(args, "path"));
                    String type = file.mediaType().toLowerCase(Locale.ROOT);
                    if (!type.startsWith("image/") && !type.equals("application/pdf")) {
                        throw new ToolInputException(file.path() + " is " + file.mediaType()
                                + "; only images and PDFs can be viewed (use workspace_read or document_read for text)");
                    }
                    if (file.size() > MAX_VIEW_BYTES) {
                        throw new ToolInputException(file.path() + " is too large to view (" + file.size() + " bytes; limit "
                                + MAX_VIEW_BYTES + ")");
                    }
                    context.showToModel(file);
                    return "You can now see " + file.path() + " directly.";
                });
    }

    /** Every workspace tool reports {@link WorkspaceException}s to the model, like {@link ToolInputException}s. */
    private static RegisteredTool tool(String name, String description, Map<String, Object> parameters, ToolHandler handler) {
        return new RegisteredTool(
                ToolDefinition.builder().name(name).description(description).parameters(parameters).build(),
                (Map<String, Object> args, ToolContext context) -> {
                    try {
                        return handler.handle(args, context);
                    } catch (WorkspaceException e) {
                        throw new ToolInputException(e.getMessage());
                    }
                });
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String replaceFirst(String text, String oldText, String newText) {
        int at = text.indexOf(oldText);
        return text.substring(0, at) + newText + text.substring(at + oldText.length());
    }
}
