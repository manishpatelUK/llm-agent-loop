package io.github.manishpateluk.llmagentloop.workspace;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * A file in a {@link Workspace}. Text and binary alike (an {@code .xlsx} is as much a workspace
 * file as a {@code .md}); {@link #content()} is always the raw bytes.
 *
 * @param path       normalized, relative, {@code /}-separated — see {@link ScopedWorkspace#normalize}
 * @param mediaType  e.g. {@code text/markdown}, {@code application/vnd.openxmlformats-officedocument.spreadsheetml.sheet}
 * @param content    the bytes; defensively copied in and out, so a stored file can't be mutated from outside
 * @param modifiedAt when it was last written
 */
public record WorkspaceFile(String path, String mediaType, byte[] content, Instant modifiedAt) {

    public WorkspaceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public int size() {
        return content.length;
    }

    /** Whether {@link #mediaType()} is a textual format that {@link #text()} can sensibly decode. */
    public boolean isText() {
        return MediaTypes.isText(mediaType);
    }

    /** The content decoded as UTF-8. */
    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public WorkspaceFileInfo info() {
        return new WorkspaceFileInfo(path, mediaType, content.length, modifiedAt);
    }
}
