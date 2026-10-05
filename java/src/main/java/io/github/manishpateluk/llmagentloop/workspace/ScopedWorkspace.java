package io.github.manishpateluk.llmagentloop.workspace;

import io.github.manishpateluk.llmagentloop.Scope;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link Workspace} bound to one {@link Scope} — what a tool receives via
 * {@code ToolContext.workspace()} — and the single place the workspace's safety rules are
 * enforced, whatever storage sits behind it:
 * <ul>
 *   <li>every path is {@link #normalize normalized} before the backing workspace sees it;</li>
 *   <li>{@link WorkspaceLimits} are checked on every write;</li>
 *   <li>there's no way to name a different scope.</li>
 * </ul>
 * One instance serves one run, and records which paths that run {@link #changedPaths() changed}.
 */
public final class ScopedWorkspace {

    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_SEGMENT_LENGTH = 255;
    private static final int MAX_DEPTH = 32;

    private final Workspace workspace;
    private final Scope scope;
    private final WorkspaceLimits limits;
    private final Set<String> changedPaths = new LinkedHashSet<>();

    ScopedWorkspace(Workspace workspace, Scope scope, WorkspaceLimits limits) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * The canonical form of a model-supplied path: {@code /}-separated and relative to the
     * workspace root. A leading {@code /} or {@code \} just means "from the root" and is dropped;
     * {@code .} segments and repeated separators collapse. Refused outright: {@code ..} segments,
     * drive letters or any {@code :}, control characters, and over-long paths.
     *
     * @throws WorkspaceException if the path can't be made safe
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new WorkspaceException("A path is required");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new WorkspaceException("Path is longer than " + MAX_PATH_LENGTH + " characters");
        }
        if (path.indexOf(':') >= 0) {
            throw new WorkspaceException("Path must not contain ':' (no drive letters or URLs): " + path);
        }
        if (path.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new WorkspaceException("Path must not contain control characters");
        }

        List<String> segments = new ArrayList<>();
        for (String segment : path.trim().replace('\\', '/').split("/")) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty() || trimmed.equals(".")) {
                continue;
            }
            if (trimmed.equals("..")) {
                throw new WorkspaceException("Path must not contain '..': " + path);
            }
            if (trimmed.length() > MAX_SEGMENT_LENGTH) {
                throw new WorkspaceException("Path segment is longer than " + MAX_SEGMENT_LENGTH + " characters");
            }
            segments.add(trimmed);
        }
        if (segments.isEmpty()) {
            throw new WorkspaceException("Path must name a file, not the workspace root: " + path);
        }
        if (segments.size() > MAX_DEPTH) {
            throw new WorkspaceException("Path is nested deeper than " + MAX_DEPTH + " levels");
        }
        return String.join("/", segments);
    }

    public Optional<WorkspaceFile> read(String path) {
        return workspace.read(scope, normalize(path));
    }

    /** {@link #read}, failing if there's no such file. */
    public WorkspaceFile require(String path) {
        String normalized = normalize(path);
        return workspace.read(scope, normalized)
                .orElseThrow(() -> new WorkspaceException("No such file: " + normalized));
    }

    /** Creates or replaces a file; {@code mediaType} {@code null} means guess from the extension. */
    public WorkspaceFile write(String path, byte[] content, String mediaType) {
        String normalized = normalize(path);
        Objects.requireNonNull(content, "content");
        if (content.length > limits.maxFileBytes()) {
            throw new WorkspaceException("File is " + content.length + " bytes; the limit is " + limits.maxFileBytes());
        }

        List<WorkspaceFileInfo> existing = workspace.list(scope);
        long otherFilesBytes = 0;
        boolean replacing = false;
        for (WorkspaceFileInfo info : existing) {
            if (info.path().equals(normalized)) {
                replacing = true;
            } else {
                otherFilesBytes += info.size();
            }
        }
        if (!replacing && existing.size() >= limits.maxFiles()) {
            throw new WorkspaceException("The workspace already holds its limit of " + limits.maxFiles() + " files");
        }
        if (otherFilesBytes + content.length > limits.maxTotalBytes()) {
            throw new WorkspaceException("Writing this would take the workspace past its "
                    + limits.maxTotalBytes() + "-byte total limit");
        }

        String type = mediaType != null ? mediaType : MediaTypes.guess(normalized, MediaTypes.OCTET_STREAM);
        WorkspaceFile file = new WorkspaceFile(normalized, type, content, Instant.now());
        workspace.write(scope, file);
        changedPaths.add(normalized);
        return file;
    }

    /** Writes UTF-8 text; the media type is guessed from the extension, defaulting to {@code text/plain}. */
    public WorkspaceFile writeText(String path, String text) {
        String normalized = normalize(path);
        return write(normalized, text.getBytes(StandardCharsets.UTF_8), MediaTypes.guess(normalized, "text/plain"));
    }

    public boolean delete(String path) {
        String normalized = normalize(path);
        boolean deleted = workspace.delete(scope, normalized);
        if (deleted) {
            changedPaths.add(normalized);
        }
        return deleted;
    }

    /** Files whose path starts with {@code prefix} (blank or {@code null} for all), sorted by path. */
    public List<WorkspaceFileInfo> list(String prefix) {
        String normalizedPrefix = (prefix == null || prefix.isBlank() || prefix.strip().equals("/"))
                ? ""
                : normalize(prefix);
        return workspace.list(scope).stream()
                .filter(info -> info.path().startsWith(normalizedPrefix))
                .sorted(Comparator.comparing(WorkspaceFileInfo::path))
                .toList();
    }

    /** Paths this view has written or deleted, in first-touched order. */
    public Set<String> changedPaths() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(changedPaths));
    }

    /** The partition key this view is bound to (already reduced to its level). */
    public Scope scope() {
        return scope;
    }
}
