package io.github.manishpateluk.llmagentloop.memory;

import io.github.manishpateluk.llmagentloop.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryMemoryStoreTest {

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final Scope alice = new Scope("acme", "alice", null);
    private final Scope bob = new Scope("acme", "bob", null);

    @Test
    void entriesAreOnlyVisibleWithinTheirOwnScope() {
        store.save(alice, "Alice prefers quarterly invoicing", List.of());

        assertThat(store.search(alice, "invoicing", 5)).hasSize(1);
        assertThat(store.search(bob, "invoicing", 5)).isEmpty();
        assertThat(store.search(bob, "", 5)).isEmpty();
    }

    @Test
    void searchRanksByKeywordOverlapAndIgnoresNonMatches() {
        store.save(alice, "The company is based in Leeds", List.of());
        store.save(alice, "Pricing is 49 per seat per month", List.of("pricing"));
        store.save(alice, "Annual pricing gets a 20 percent discount", List.of("pricing", "discount"));

        List<MemoryEntry> found = store.search(alice, "pricing discount", 5);

        assertThat(found).extracting(MemoryEntry::content).containsExactly(
                "Annual pricing gets a 20 percent discount",
                "Pricing is 49 per seat per month");
    }

    @Test
    void blankQueryReturnsMostRecentFirstUpToTheLimit() throws InterruptedException {
        store.save(alice, "first", List.of());
        Thread.sleep(2);
        store.save(alice, "second", List.of());
        Thread.sleep(2);
        store.save(alice, "third", List.of());

        assertThat(store.search(alice, " ", 2)).extracting(MemoryEntry::content).containsExactly("third", "second");
    }

    @Test
    void deleteOnlyRemovesWithinTheGivenScope() {
        MemoryEntry entry = store.save(alice, "secret plan", List.of());

        assertThat(store.delete(bob, entry.id())).isFalse();
        assertThat(store.delete(alice, entry.id())).isTrue();
        assertThat(store.search(alice, "secret", 5)).isEmpty();
    }

    @Test
    void scopedViewIsBoundToItsScope() {
        ScopedMemory aliceView = store.scopedTo(alice);
        aliceView.save("Alice's fact", List.of());

        assertThat(store.scopedTo(bob).search("fact", 5)).isEmpty();
        assertThat(aliceView.search("fact", 5)).hasSize(1);
    }

    @Test
    void noneStoreFindsNothingAndRefusesToSaveWithAHelpfulMessage() {
        assertThat(MemoryStore.NONE.search(alice, "anything", 5)).isEmpty();
        assertThatThrownBy(() -> MemoryStore.NONE.save(alice, "x", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentLoop.builder().memory");
    }
}
