package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmagentloop.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorIndexAndChunkerTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");
    private static final Scope BOB = Scope.of("acme", "bob", "s1");

    private static VectorIndex.Entry entry(String id, String source, float[] vector, String model) {
        return new VectorIndex.Entry(id, source, "v1", "text " + id, vector, model, Map.of());
    }

    @Test
    void searchRanksByCosineSimilarity() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        index.upsert("c", ALICE, List.of(
                entry("near", "a", new float[]{1, 0.1f}, "m"),
                entry("far", "b", new float[]{0, 1}, "m"),
                entry("middle", "c", new float[]{1, 1}, "m")));

        List<VectorIndex.Match> matches = index.search("c", ALICE, new float[]{1, 0}, "m", 2);

        assertThat(matches).extracting(match -> match.entry().id()).containsExactly("near", "middle");
        assertThat(matches.getFirst().score()).isGreaterThan(0.99);
    }

    @Test
    void keepsScopesAndCollectionsApartAndOnlyComparesTheSameModel() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        index.upsert("c", ALICE, List.of(entry("alice", "a", new float[]{1, 0}, "m")));
        index.upsert("c", BOB, List.of(entry("bob", "b", new float[]{1, 0}, "m")));
        index.upsert("other", ALICE, List.of(entry("other", "o", new float[]{1, 0}, "m")));
        index.upsert("c", ALICE, List.of(entry("other-model", "x", new float[]{1, 0}, "m2")));

        assertThat(index.search("c", ALICE, new float[]{1, 0}, "m", 10))
                .extracting(match -> match.entry().id()).containsExactly("alice");
    }

    @Test
    void deleteSourceRemovesAllItsEntriesAndSourceVersionsTracksWhatIsIndexed() {
        InMemoryVectorIndex index = new InMemoryVectorIndex();
        index.upsert("c", ALICE, List.of(entry("a#0", "a", new float[]{1}, "m"), entry("a#1", "a", new float[]{1}, "m"),
                entry("b#0", "b", new float[]{1}, "m")));
        assertThat(index.sourceVersions("c", ALICE)).isEqualTo(Map.of("a", "v1", "b", "v1"));

        index.deleteSource("c", ALICE, "a");

        assertThat(index.search("c", ALICE, new float[]{1}, "m", 10)).extracting(m -> m.entry().id()).containsExactly("b#0");
        assertThat(index.sourceVersions("c", ALICE)).containsOnlyKeys("b");
    }

    @Test
    void shortTextIsOneChunk() {
        assertThat(TextChunker.chunk("Hello world.")).containsExactly(new TextChunker.Chunk("Hello world.", 0));
        assertThat(TextChunker.chunk("   ")).isEmpty();
    }

    @Test
    void longTextSplitsAtParagraphsWithOverlapAndCoversEverything() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("Paragraph ").append(i).append(" talks about topic ").append(i).append(" at some length here.\n\n");
        }
        List<TextChunker.Chunk> chunks = TextChunker.chunk(text.toString(), 300, 60);

        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(300));
        // Chunks end at paragraph boundaries, and each starts at a word.
        assertThat(chunks.subList(0, chunks.size() - 1)).allSatisfy(chunk -> assertThat(chunk.text()).endsWith("."));
        assertThat(chunks).allSatisfy(chunk -> assertThat(text.charAt(chunk.start())).isNotEqualTo(' '));
        for (int i = 0; i < 40; i++) {
            String sentence = "Paragraph " + i + " talks";
            assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.text()).contains(sentence));
        }
        // Consecutive chunks overlap.
        for (int i = 1; i < chunks.size(); i++) {
            TextChunker.Chunk previous = chunks.get(i - 1);
            assertThat(chunks.get(i).start()).isLessThan(previous.start() + previous.text().length());
        }
    }

    @Test
    void textWithNoBoundariesIsStillSplit() {
        List<TextChunker.Chunk> chunks = TextChunker.chunk("x".repeat(1000), 300, 50);
        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks.getLast().start() + chunks.getLast().text().length()).isEqualTo(1000);
    }
}
