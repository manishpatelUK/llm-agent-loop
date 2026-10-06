package io.github.manishpateluk.llmagentloop.workspace;

import io.github.manishpateluk.llmagentloop.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedWorkspaceTest {

    private final InMemoryWorkspace backing = new InMemoryWorkspace();
    private final Scope alice = new Scope("acme", "alice", null);
    private final Scope bob = new Scope("acme", "bob", null);

    @ParameterizedTest
    @CsvSource({
            "notes.md, notes.md",
            "/notes.md, notes.md",
            "\\reports\\q3.md, reports/q3.md",
            "./reports//q3.md, reports/q3.md",
            "reports/./drafts/q3.md, reports/drafts/q3.md",
    })
    void normalizesHarmlessPathVariations(String input, String expected) {
        assertThat(ScopedWorkspace.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../secrets.txt",
            "reports/../../etc/passwd",
            "C:/Windows/system.ini",
            "c:secrets",
            "file:///etc/passwd",
            "/",
            "  ",
            "reports/\u0000evil",
    })
    void refusesPathsThatCouldEscapeOrAreMeaningless(String input) {
        assertThatThrownBy(() -> ScopedWorkspace.normalize(input)).isInstanceOf(WorkspaceException.class);
    }

    @Test
    void filesAreOnlyVisibleWithinTheirOwnScope() {
        backing.scopedTo(alice, WorkspaceLimits.DEFAULT).writeText("plan.md", "alice's plan");

        assertThat(backing.scopedTo(bob, WorkspaceLimits.DEFAULT).read("plan.md")).isEmpty();
        assertThat(backing.scopedTo(alice, WorkspaceLimits.DEFAULT).require("plan.md").text()).isEqualTo("alice's plan");
    }

    @Test
    void writeTextGuessesTheMediaTypeFromTheExtension() {
        ScopedWorkspace workspace = backing.scopedTo(alice, WorkspaceLimits.DEFAULT);

        assertThat(workspace.writeText("data.csv", "a,b").mediaType()).isEqualTo("text/csv");
        assertThat(workspace.writeText("README", "hi").mediaType()).isEqualTo("text/plain");
    }

    @Test
    void enforcesPerFileSizeLimit() {
        ScopedWorkspace workspace = backing.scopedTo(alice, new WorkspaceLimits(10, 5, 100));

        assertThatThrownBy(() -> workspace.writeText("big.txt", "123456"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void enforcesFileCountLimitButAllowsReplacingAnExistingFile() {
        ScopedWorkspace workspace = backing.scopedTo(alice, new WorkspaceLimits(2, 100, 1000));
        workspace.writeText("a.txt", "a");
        workspace.writeText("b.txt", "b");

        assertThatThrownBy(() -> workspace.writeText("c.txt", "c")).isInstanceOf(WorkspaceException.class);
        workspace.writeText("a.txt", "replaced");
        assertThat(workspace.require("a.txt").text()).isEqualTo("replaced");
    }

    @Test
    void enforcesTotalSizeLimitCountingAReplacedFileOnlyOnce() {
        ScopedWorkspace workspace = backing.scopedTo(alice, new WorkspaceLimits(10, 100, 10));
        workspace.writeText("a.txt", "12345");
        workspace.writeText("a.txt", "1234567890");

        assertThatThrownBy(() -> workspace.writeText("b.txt", "x")).isInstanceOf(WorkspaceException.class);
    }

    @Test
    void listFiltersByPrefixAndSortsByPath() {
        ScopedWorkspace workspace = backing.scopedTo(alice, WorkspaceLimits.DEFAULT);
        workspace.writeText("reports/q4.md", "x");
        workspace.writeText("notes.md", "x");
        workspace.writeText("reports/q3.md", "x");

        assertThat(workspace.list("reports/")).extracting(WorkspaceFileInfo::path)
                .containsExactly("reports/q3.md", "reports/q4.md");
        assertThat(workspace.list(null)).extracting(WorkspaceFileInfo::path)
                .containsExactly("notes.md", "reports/q3.md", "reports/q4.md");
    }

    @Test
    void recordsChangedPathsIncludingDeletesButNotReads() {
        ScopedWorkspace setup = backing.scopedTo(alice, WorkspaceLimits.DEFAULT);
        setup.writeText("existing.md", "x");

        ScopedWorkspace run = backing.scopedTo(alice, WorkspaceLimits.DEFAULT);
        run.read("existing.md");
        run.writeText("/new.md", "x");
        run.delete("existing.md");
        run.delete("never-existed.md");

        assertThat(run.changedPaths()).containsExactlyInAnyOrder("new.md", "existing.md");
    }

    @Test
    void storedContentCannotBeMutatedFromOutside() {
        ScopedWorkspace workspace = backing.scopedTo(alice, WorkspaceLimits.DEFAULT);
        byte[] bytes = "original".getBytes();
        workspace.write("f.txt", bytes, null);
        bytes[0] = 'X';
        workspace.require("f.txt").content()[1] = 'Y';

        assertThat(workspace.require("f.txt").text()).isEqualTo("original");
    }

    @Test
    void refusesPathsThatAreTooLongTooDeepOrHaveOversizedSegments() {
        assertThatThrownBy(() -> ScopedWorkspace.normalize("a/".repeat(200) + "x".repeat(200)))
                .isInstanceOf(WorkspaceException.class).hasMessageContaining("longer than 512");
        assertThatThrownBy(() -> ScopedWorkspace.normalize("x".repeat(256) + ".md"))
                .isInstanceOf(WorkspaceException.class).hasMessageContaining("segment is longer than 255");
        assertThatThrownBy(() -> ScopedWorkspace.normalize("d/".repeat(33) + "f.md"))
                .isInstanceOf(WorkspaceException.class).hasMessageContaining("deeper than 32");
        // Right at the limits is fine.
        assertThat(ScopedWorkspace.normalize("x".repeat(255))).hasSize(255);
        assertThat(ScopedWorkspace.normalize("d/".repeat(31) + "f.md")).endsWith("f.md");
    }
}
