package io.github.manishpateluk.llmagentloop.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One remembered fact.
 *
 * @param id        store-assigned; what {@code memory_forget} refers to
 * @param content   the fact itself, in the model's own words
 * @param tags      optional labels to help later searches; never {@code null}
 * @param createdAt when it was saved
 * @param metadata  what a store knows about the fact beyond its words; never {@code null}. Two keys are shown to the
 *                  model when the entry is recalled into a step: {@link #SOURCE} (where it came from, e.g. "user")
 *                  and {@link #AGE} (how old it is, e.g. "3 days ago"), as {@code [source, age] content}.
 */
public record MemoryEntry(String id, String content, List<String> tags, Instant createdAt, Map<String, String> metadata) {

    /** Metadata key: where the fact came from, in a word or two the model can weigh ("user", "conversation"). */
    public static final String SOURCE = "source";
    /** Metadata key: how old the fact is, as the model should read it ("3 days ago"). */
    public static final String AGE = "age";

    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        tags = tags == null ? List.of() : List.copyOf(tags);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** An entry with no metadata. */
    public MemoryEntry(String id, String content, List<String> tags, Instant createdAt) {
        this(id, content, tags, createdAt, Map.of());
    }

    /** This entry with {@code metadata} in place of its own. */
    public MemoryEntry withMetadata(Map<String, String> metadata) {
        return new MemoryEntry(id, content, tags, createdAt, metadata);
    }

    /**
     * How the entry reads when recalled into a step: its content, led by {@code [source, age]} when it has either.
     */
    public String recalledText() {
        String source = blankToNull(metadata.get(SOURCE));
        String age = blankToNull(metadata.get(AGE));
        if (source == null && age == null) {
            return content;
        }
        String label = source == null ? age : age == null ? source : source + ", " + age;
        return "[" + label + "] " + content;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
