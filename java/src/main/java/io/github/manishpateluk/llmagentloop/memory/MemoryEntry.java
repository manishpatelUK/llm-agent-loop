package io.github.manishpateluk.llmagentloop.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One remembered fact.
 *
 * @param id        store-assigned; what {@code memory_forget} refers to
 * @param content   the fact itself, in the model's own words
 * @param tags      optional labels to help later searches; never {@code null}
 * @param createdAt when it was saved
 */
public record MemoryEntry(String id, String content, List<String> tags, Instant createdAt) {

    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
