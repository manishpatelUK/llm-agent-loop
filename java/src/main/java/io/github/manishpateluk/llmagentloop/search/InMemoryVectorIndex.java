package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link VectorIndex} in this process's heap with exact (brute-force) cosine search: fine for
 * development, tests and modest data (tens of thousands of chunks), lost on restart. Use a real
 * vector database for production volumes.
 */
public final class InMemoryVectorIndex implements VectorIndex {

    private record Partition(String collection, Scope scope) {
    }

    private final Map<Partition, Map<String, Entry>> entries = new ConcurrentHashMap<>();

    @Override
    public void upsert(String collection, Scope scope, List<Entry> toAdd) {
        Map<String, Entry> partition = entries.computeIfAbsent(new Partition(collection, scope), key -> new ConcurrentHashMap<>());
        for (Entry entry : toAdd) {
            partition.put(entry.id(), entry);
        }
    }

    @Override
    public void deleteSource(String collection, Scope scope, String source) {
        Map<String, Entry> partition = entries.get(new Partition(collection, scope));
        if (partition != null) {
            partition.values().removeIf(entry -> entry.source().equals(source));
        }
    }

    @Override
    public List<Match> search(String collection, Scope scope, float[] query, String model, int limit) {
        Map<String, Entry> partition = entries.getOrDefault(new Partition(collection, scope), Map.of());
        List<Match> matches = new ArrayList<>();
        for (Entry entry : partition.values()) {
            if (entry.model().equals(model) && entry.vector().length == query.length) {
                matches.add(new Match(entry, cosine(query, entry.vector())));
            }
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed());
        return matches.size() > limit ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
    }

    @Override
    public Map<String, String> sourceVersions(String collection, Scope scope) {
        Map<String, String> versions = new HashMap<>();
        for (Entry entry : entries.getOrDefault(new Partition(collection, scope), Map.of()).values()) {
            versions.put(entry.source(), entry.version());
        }
        return versions;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
