package io.github.manishpateluk.llmagentloop.memory;

import io.github.manishpateluk.llmagentloop.Scope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * A {@link MemoryStore} held entirely in this process's heap: lost on restart and unbounded in
 * size, so meant for development, tests, and single-process tools — not as a production backend.
 *
 * <p>Search is plain keyword overlap: an entry scores one point per distinct query word found in
 * its content or tags, ties going to the newer entry. No embeddings, no model to load.
 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final Map<Scope, List<MemoryEntry>> entries = new ConcurrentHashMap<>();

    @Override
    public MemoryEntry save(Scope scope, String content, List<String> tags) {
        MemoryEntry entry = new MemoryEntry(UUID.randomUUID().toString(), content, tags, Instant.now());
        entries.computeIfAbsent(scope, key -> new CopyOnWriteArrayList<>()).add(entry);
        return entry;
    }

    @Override
    public List<MemoryEntry> search(Scope scope, String query, int limit) {
        List<MemoryEntry> candidates = entries.getOrDefault(scope, List.of());
        Comparator<MemoryEntry> newestFirst = Comparator.comparing(MemoryEntry::createdAt).reversed();

        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return candidates.stream().sorted(newestFirst).limit(limit).toList();
        }

        record Scored(MemoryEntry entry, long score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (MemoryEntry entry : candidates) {
            Set<String> entryTerms = terms(entry.content() + " " + String.join(" ", entry.tags()));
            long score = queryTerms.stream().filter(entryTerms::contains).count();
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingLong(Scored::score).reversed()
                        .thenComparing(Scored::entry, newestFirst))
                .limit(limit)
                .map(Scored::entry)
                .toList();
    }

    @Override
    public boolean delete(Scope scope, String id) {
        List<MemoryEntry> list = entries.get(scope);
        return list != null && list.removeIf(entry -> entry.id().equals(id));
    }

    /** Lower-cased words of two or more letters/digits. */
    private static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 2)
                .collect(Collectors.toSet());
    }
}
