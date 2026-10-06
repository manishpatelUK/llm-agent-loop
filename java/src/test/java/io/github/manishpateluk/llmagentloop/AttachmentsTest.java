package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.lastToolResult;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

class AttachmentsTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private final ScriptedModel model = new ScriptedModel();
    private final InMemoryWorkspace workspace = new InMemoryWorkspace();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void imagesAndPdfsGoToTheModelDirectlyTextIsInlinedAndEverythingIsSaved() {
        AgentLoop loop = model.loop().workspace(workspace).build();

        AgentLoopResult result = loop.runAndWait(request()
                .attachments(List.of(
                        InputFile.of("receipt.png", PNG),
                        InputFile.of("contract.pdf", "%PDF-1.7".getBytes()),
                        InputFile.of("sales.csv", "region,amount\nNorth,10".getBytes()),
                        InputFile.of("model.xlsx", new byte[]{1, 2}))));

        Request sent = model.requests.getFirst();
        assertThat(sent.getAttachments()).extracting(Attachment::getFilename).containsExactly("receipt.png", "contract.pdf");
        String note = note(sent);
        assertThat(note).contains("4 file(s)",
                "receipt.png (image/png, 7 bytes), saved in the workspace at uploads/receipt.png — attached for you to see directly",
                "sales.csv (text/csv, 22 bytes), saved in the workspace at uploads/sales.csv:\n[[untrusted-content ",
                "source=attachment sales.csv]]\n```\nregion,amount\nNorth,10\n```\n[[end untrusted-content ",
                "model.xlsx", "uploads/model.xlsx");
        assertThat(result.changedFiles()).containsExactly(
                "uploads/receipt.png", "uploads/contract.pdf", "uploads/sales.csv", "uploads/model.xlsx");
        assertThat(workspace.scopedTo(ALICE.atLevel(ScopeLevel.USER), WorkspaceLimits.DEFAULT).require("uploads/sales.csv").text())
                .isEqualTo("region,amount\nNorth,10");
    }

    @Test
    void savingCanBeTurnedOff() {
        AgentLoop loop = model.loop().workspace(workspace).build();

        AgentLoopResult result = loop.runAndWait(request()
                .saveAttachments(false)
                .attachments(List.of(InputFile.of("notes.txt", "hello".getBytes()), InputFile.of("photo.png", PNG))));

        assertThat(result.changedFiles()).isEmpty();
        assertThat(note(model.requests.getFirst())).contains("notes.txt (text/plain, 5 bytes):\n[[untrusted-content", "```\nhello\n```")
                .doesNotContain("saved in the workspace");
        assertThat(model.requests.getFirst().getAttachments()).hasSize(1);
    }

    @Test
    void withoutAWorkspaceAttachmentsStillReachTheModel() {
        AgentLoop loop = model.loop().build();

        AgentLoopResult result = loop.runAndWait(request().attachments(List.of(InputFile.of("photo.png", PNG))));

        assertThat(result.changedFiles()).isEmpty();
        assertThat(model.requests.getFirst().getAttachments()).hasSize(1);
    }

    @Test
    void uploadNamesCannotEscapeTheUploadsFolder() {
        AgentLoop loop = model.loop().workspace(workspace).build();

        AgentLoopResult result = loop.runAndWait(request().attachments(List.of(
                InputFile.of("../../etc/passwd.txt", "x".getBytes()),
                InputFile.of("C:\\Users\\me\\report.txt", "y".getBytes()))));

        assertThat(result.changedFiles()).containsExactly("uploads/passwd.txt", "uploads/report.txt");
    }

    @Test
    void attachmentsAreSeenOnEveryStepOfTheRun() {
        AgentLoop loop = model.loop().workspace(workspace)
                .tools(new ToolRegistry().registerAll(WorkspaceTools.all())).build();
        model.respond(request -> lastToolResult(request) == null
                ? toolCall("l", "workspace_list", Map.of())
                : complete("done"));

        loop.runAndWait(request().attachments(List.of(InputFile.of("photo.png", PNG))));

        assertThat(model.requests).hasSize(2).allSatisfy(r -> assertThat(r.getAttachments()).hasSize(1));
    }

    @Test
    void workspaceViewShowsAFileToTheModelFromTheNextStepOn() {
        workspace.scopedTo(ALICE.atLevel(ScopeLevel.USER), WorkspaceLimits.DEFAULT).write("charts/q3.png", PNG, null);
        AgentLoop loop = model.loop().workspace(workspace)
                .tools(new ToolRegistry().registerAll(WorkspaceTools.all())).build();
        model.respond(request -> lastToolResult(request) == null
                ? toolCall("v", "workspace_view", Map.of("path", "charts/q3.png"))
                : complete("It's a bar chart."));

        AgentLoopResult result = loop.runAndWait(request());

        assertThat(model.requests.get(0).getAttachments()).isEmpty();
        assertThat(model.requests.get(1).getAttachments()).extracting(Attachment::getFilename).containsExactly("charts/q3.png");
        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("You can now see charts/q3.png directly.");
        assertThat(result.finalResponse().getContent()).isEqualTo("It's a bar chart.");
    }

    @Test
    void workspaceViewRefusesFilesItCannotShow() {
        workspace.scopedTo(ALICE.atLevel(ScopeLevel.USER), WorkspaceLimits.DEFAULT).writeText("notes.md", "hi");
        AgentLoop loop = model.loop().workspace(workspace)
                .tools(new ToolRegistry().registerAll(WorkspaceTools.all())).build();
        model.respond(request -> lastToolResult(request) == null
                ? toolCall("v", "workspace_view", Map.of("path", "notes.md"))
                : complete("ok"));

        loop.runAndWait(request());

        assertThat(lastToolResult(model.requests.get(1))).startsWith("Error: notes.md is text/markdown; only images and PDFs");
        assertThat(model.requests.get(1).getAttachments()).isEmpty();
    }

    private static LoopRequest.LoopRequestBuilder request() {
        return LoopRequest.builder().prompt("Take a look").scope(ALICE).agentProfile(RECURSIVE);
    }

    private static String note(Request request) {
        return request.getHistory().stream().map(Message::getContent)
                .filter(content -> content.startsWith("The user attached"))
                .findFirst().orElseThrow();
    }
}
