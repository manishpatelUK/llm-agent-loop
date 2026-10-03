package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltinToolsTest {

    private static final Pattern PROVIDER_SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final InMemoryMemoryStore memory = new InMemoryMemoryStore();
    private final InMemoryWorkspace workspace = new InMemoryWorkspace();
    private final Scope scope = new Scope("acme", "alice", null);
    private final ToolContext context = new ToolContext(
            UUID.randomUUID(), 0, Scope.of("acme", "alice", "s1"),
            memory.scopedTo(scope), workspace.scopedTo(scope, WorkspaceLimits.DEFAULT));

    @Test
    void everyBuiltinToolHasAProviderSafeNameAndAnObjectSchema() {
        List<RegisteredTool> all = new java.util.ArrayList<>(MemoryTools.all());
        all.addAll(WorkspaceTools.all());

        assertThat(all).allSatisfy(tool -> {
            assertThat(tool.definition().getName()).matches(PROVIDER_SAFE_NAME);
            assertThat(tool.definition().getParameters()).containsEntry("type", "object");
            assertThat(tool.definition().getDescription()).isNotBlank();
        });
        assertThat(all).extracting(tool -> tool.definition().getName()).doesNotHaveDuplicates();
    }

    @Test
    void memoryToolsSaveSearchAndForgetWithinTheContextsScope() {
        String saved = call(MemoryTools.save(), Map.of("content", "Launch is planned for March", "tags", List.of("launch")));
        String id = saved.replaceAll(".*id ([0-9a-f-]+)\\..*", "$1");

        assertThat(call(MemoryTools.search(), Map.of("query", "launch"))).contains("Launch is planned for March", id);
        assertThat(call(MemoryTools.forget(), Map.of("id", id))).contains("Forgot");
        assertThat(call(MemoryTools.search(), Map.of("query", "launch"))).isEqualTo("No matching memories.");
    }

    @Test
    void forgettingAnUnknownMemoryIsAFixableErrorNotACrash() {
        assertThatThrownBy(() -> call(MemoryTools.forget(), Map.of("id", "nope")))
                .isInstanceOf(ToolInputException.class);
    }

    @Test
    void missingRequiredArgumentIsAFixableError() {
        assertThatThrownBy(() -> call(MemoryTools.save(), Map.of()))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("content");
    }

    @Test
    void workspaceWriteListReadRoundTrip() {
        call(WorkspaceTools.write(), Map.of("path", "drafts/email.md", "content", "Hello team"));

        assertThat(call(WorkspaceTools.list(), Map.of())).contains("drafts/email.md", "text/markdown");
        assertThat(call(WorkspaceTools.read(), Map.of("path", "/drafts/email.md"))).isEqualTo("Hello team");
    }

    @Test
    void readingALongFileReturnsAPieceAndSaysHowToContinue() {
        call(WorkspaceTools.write(), Map.of("path", "long.txt", "content", "abcdefghij"));

        String first = call(WorkspaceTools.read(), Map.of("path", "long.txt", "max_chars", 4));
        assertThat(first).startsWith("abcd").contains("offset 4");
        assertThat(call(WorkspaceTools.read(), Map.of("path", "long.txt", "offset", 4, "max_chars", 100))).isEqualTo("efghij");
    }

    @Test
    void readingABinaryFileDescribesItInsteadOfDumpingBytes() {
        context.workspace().write("model.xlsx", new byte[]{1, 2, 3}, null);

        assertThat(call(WorkspaceTools.read(), Map.of("path", "model.xlsx")))
                .contains("binary", MediaTypes.XLSX, "3 bytes");
    }

    @Test
    void editRequiresAUniqueMatchUnlessReplaceAll() {
        call(WorkspaceTools.write(), Map.of("path", "plan.md", "content", "TODO one\nTODO two"));

        assertThatThrownBy(() -> call(WorkspaceTools.edit(), Map.of("path", "plan.md", "old_text", "TODO", "new_text", "DONE")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("2 times");

        call(WorkspaceTools.edit(), Map.of("path", "plan.md", "old_text", "TODO one", "new_text", "DONE one"));
        assertThat(call(WorkspaceTools.read(), Map.of("path", "plan.md"))).isEqualTo("DONE one\nTODO two");

        Map<String, Object> all = new HashMap<>(Map.of("path", "plan.md", "old_text", "TODO", "new_text", "DONE"));
        all.put("replace_all", true);
        call(WorkspaceTools.edit(), all);
        assertThat(call(WorkspaceTools.read(), Map.of("path", "plan.md"))).isEqualTo("DONE one\nDONE two");
    }

    @Test
    void editKeepsTheFilesMediaType() {
        call(WorkspaceTools.write(), Map.of("path", "data.csv", "content", "a,b"));
        call(WorkspaceTools.edit(), Map.of("path", "data.csv", "old_text", "a", "new_text", "x"));

        assertThat(context.workspace().require("data.csv").mediaType()).isEqualTo("text/csv");
    }

    @Test
    void searchReportsPathLineAndText() {
        call(WorkspaceTools.write(), Map.of("path", "a.md", "content", "intro\nRevenue grew 20%\noutro"));
        call(WorkspaceTools.write(), Map.of("path", "b.md", "content", "nothing here"));

        assertThat(call(WorkspaceTools.search(), Map.of("query", "revenue"))).isEqualTo("a.md:2: Revenue grew 20%");
    }

    @Test
    void unsafePathsAndMissingFilesComeBackAsFixableErrors() {
        assertThatThrownBy(() -> call(WorkspaceTools.write(), Map.of("path", "../escape.md", "content", "x")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> call(WorkspaceTools.read(), Map.of("path", "missing.md")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("No such file");
        assertThatThrownBy(() -> call(WorkspaceTools.delete(), Map.of("path", "missing.md")))
                .isInstanceOf(ToolInputException.class);
    }

    private String call(RegisteredTool tool, Map<String, Object> args) {
        return tool.handler().handle(args, context);
    }
}
