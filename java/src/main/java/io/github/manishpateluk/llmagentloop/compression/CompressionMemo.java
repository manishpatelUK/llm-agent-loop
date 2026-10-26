package io.github.manishpateluk.llmagentloop.compression;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.provider.Provider;

/**
 * Keeps a self-compressing router's compressions stable from call to call. An agent loop's history only grows, so
 * when a request's history starts with one this router already compressed for the same model and instructions,
 * the earlier compressed version is reused and only the new messages are appended; compression runs again only
 * when that no longer fits. Without this, every call over the limit would be compressed afresh: a different
 * prefix each time (so nothing is read from the provider's prompt cache) and, for {@code LLM_SUMMARIZATION}, a
 * summarization call each time.
 *
 * <p>Remembers the {@link #MAX_ENTRIES} most recent compressions; thread-safe, since one router serves many runs.
 */
final class CompressionMemo {

    static final int MAX_ENTRIES = 64;

    private record Entry(Provider provider, String model, String systemInstructions, List<Message> original,
                         List<Message> compressed) {
    }

    private final LinkedList<Entry> entries = new LinkedList<>();

    /**
     * {@code request} compressed by {@code compressor} (which returns its argument unchanged when it already fits),
     * starting from an earlier compression of the start of its history when there is one.
     */
    Request compress(Request request, Provider provider, String model, UnaryOperator<Request> compressor) {
        Entry earlier = longestPrefix(request, provider, model);
        Request candidate = earlier == null ? request : request.toBuilder().history(append(earlier.compressed(),
                request.getHistory().subList(earlier.original().size(), request.getHistory().size()))).build();
        Request result = compressor.apply(candidate);
        if (!result.getHistory().equals(request.getHistory())) {
            remember(new Entry(provider, model, request.getSystemInstructions(), request.getHistory(), result.getHistory()));
        }
        return result;
    }

    private synchronized Entry longestPrefix(Request request, Provider provider, String model) {
        Entry best = null;
        List<Message> history = request.getHistory();
        for (Entry entry : entries) {
            if (entry.provider() == provider && entry.model().equals(model)
                    && Objects.equals(entry.systemInstructions(), request.getSystemInstructions())
                    && entry.original().size() <= history.size()
                    && (best == null || entry.original().size() > best.original().size())
                    && history.subList(0, entry.original().size()).equals(entry.original())) {
                best = entry;
            }
        }
        if (best != null) {
            entries.remove(best);
            entries.addFirst(best);
        }
        return best;
    }

    private synchronized void remember(Entry entry) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry existing = it.next();
            if (existing.provider() == entry.provider() && existing.model().equals(entry.model())
                    && existing.original().equals(entry.original())) {
                it.remove();
            }
        }
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    private static List<Message> append(List<Message> start, List<Message> rest) {
        List<Message> all = new ArrayList<>(start.size() + rest.size());
        all.addAll(start);
        all.addAll(rest);
        return List.copyOf(all);
    }
}
