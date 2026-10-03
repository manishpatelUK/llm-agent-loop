package io.github.manishpateluk.llmagentloop.workspace;

import java.time.Instant;

/** A {@link WorkspaceFile}'s metadata, without its content — what listings return. */
public record WorkspaceFileInfo(String path, String mediaType, long size, Instant modifiedAt) {
}
