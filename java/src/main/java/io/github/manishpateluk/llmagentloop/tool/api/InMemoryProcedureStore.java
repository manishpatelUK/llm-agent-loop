package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A {@link ProcedureStore} held in this process's heap: lost on restart, so meant for
 * development, tests and single-process tools. Search is keyword overlap with each procedure's
 * name, description and connection, ties going to the most recently saved.
 */
public final class InMemoryProcedureStore implements ProcedureStore {

    private final Map<Scope, Map<String, List<Procedure>>> procedures = new ConcurrentHashMap<>();

    @Override
    public Procedure save(Scope scope, Procedure procedure) {
        Map<String, List<Procedure>> byName = procedures.computeIfAbsent(scope, key -> new ConcurrentHashMap<>());
        synchronized (byName) {
            List<Procedure> versions = byName.computeIfAbsent(procedure.name(), key -> new ArrayList<>());
            Procedure saved = procedure.withVersion(versions.size() + 1, Instant.now());
            versions.add(saved);
            return saved;
        }
    }

    @Override
    public Optional<Procedure> latest(Scope scope, String name) {
        List<Procedure> versions = versions(scope, name);
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
    }

    @Override
    public Optional<Procedure> version(Scope scope, String name, int version) {
        return versions(scope, name).stream().filter(p -> p.version() == version).findFirst();
    }

    @Override
    public List<Procedure> versions(Scope scope, String name) {
        Map<String, List<Procedure>> byName = procedures.get(scope);
        if (byName == null) {
            return List.of();
        }
        synchronized (byName) {
            return List.copyOf(byName.getOrDefault(name, List.of()));
        }
    }

    @Override
    public List<Procedure> search(Scope scope, String query, int limit) {
        Map<String, List<Procedure>> byName = procedures.get(scope);
        if (byName == null) {
            return List.of();
        }
        List<Procedure> latest;
        synchronized (byName) {
            latest = byName.values().stream().filter(v -> !v.isEmpty()).map(List::getLast).toList();
        }
        Comparator<Procedure> newestFirst = Comparator.comparing(Procedure::savedAt).reversed();
        Set<String> terms = terms(query);
        if (terms.isEmpty()) {
            return latest.stream().sorted(newestFirst).limit(limit).toList();
        }
        record Scored(Procedure procedure, long score) {
        }
        return latest.stream()
                .map(p -> {
                    Set<String> words = terms(p.name().replace('_', ' ') + " " + p.description() + " " + p.connection());
                    return new Scored(p, terms.stream().filter(words::contains).count());
                })
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingLong(Scored::score).reversed().thenComparing(Scored::procedure, newestFirst))
                .limit(limit)
                .map(Scored::procedure)
                .toList();
    }

    @Override
    public boolean delete(Scope scope, String name) {
        Map<String, List<Procedure>> byName = procedures.get(scope);
        if (byName == null) {
            return false;
        }
        synchronized (byName) {
            return byName.remove(name) != null;
        }
    }

    private static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 2)
                .collect(Collectors.toSet());
    }
}
