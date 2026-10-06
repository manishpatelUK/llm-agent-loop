package io.github.manishpateluk.llmagentloop.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long text into overlapping chunks for embedding — each small enough to embed and to show
 * as a search result, overlapping so a passage split across a boundary is still found. Prefers to
 * break at paragraph, then line, then sentence, then word boundaries.
 */
final class TextChunker {

    static final int CHUNK_CHARS = 1_500;
    static final int OVERLAP_CHARS = 200;

    private TextChunker() {
    }

    /** A chunk and where it starts in the original text. */
    record Chunk(String text, int start) {
    }

    static List<Chunk> chunk(String text) {
        return chunk(text, CHUNK_CHARS, OVERLAP_CHARS);
    }

    static List<Chunk> chunk(String text, int size, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                end = breakPoint(text, start + size / 2, end);
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(new Chunk(piece, start));
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
            // Begin the next chunk at a word boundary rather than mid-word.
            while (start < end && !Character.isWhitespace(text.charAt(start - 1))) {
                start++;
            }
        }
        return chunks;
    }

    /** The best boundary in {@code [min, max]}: paragraph, line, sentence, word — else {@code max}. */
    private static int breakPoint(String text, int min, int max) {
        for (String boundary : new String[]{"\n\n", "\n", ". ", " "}) {
            int at = text.lastIndexOf(boundary, max - boundary.length());
            if (at >= min) {
                return at + boundary.length();
            }
        }
        return max;
    }
}
