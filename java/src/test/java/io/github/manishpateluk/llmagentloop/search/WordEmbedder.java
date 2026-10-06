package io.github.manishpateluk.llmagentloop.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic stand-in for an embeddings model: each word lands in one of 64 dimensions, and a
 * few synonyms share a dimension — enough to test "found by meaning, not by exact word".
 */
final class WordEmbedder implements Embedder {

    static final Map<String, String> SYNONYMS = Map.of(
            "residence", "lives", "home", "lives", "live", "lives",
            "cancel", "termination", "terminate", "termination", "notice", "termination");

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger texts = new AtomicInteger();
    volatile RuntimeException failWith;
    /** If set, embedding waits for it, to show who waits on indexing. */
    volatile java.util.concurrent.CountDownLatch gate;

    @Override
    public Embeddings embed(List<String> input) {
        calls.incrementAndGet();
        texts.addAndGet(input.size());
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        if (failWith != null) {
            throw failWith;
        }
        List<float[]> vectors = new ArrayList<>();
        long tokens = 0;
        for (String text : input) {
            float[] vector = new float[64];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() < 3) {
                    continue;
                }
                tokens++;
                vector[Math.floorMod(SYNONYMS.getOrDefault(word, word).hashCode(), 64)] += 1;
            }
            vectors.add(vector);
        }
        return new Embeddings(vectors, "test/words-64", null, tokens);
    }
}
