package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static org.assertj.core.api.Assertions.assertThat;

class AttachmentDropWarningTest {

    private static final String TEXT_ONLY = "test-text-only-model";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private final ScriptedModel model = new ScriptedModel();

    @BeforeEach
    void registerTextOnlyModel() {
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(TEXT_ONLY)
                .contextWindowTokens(100_000).maxOutputTokens(4_000).supportsTools(true)
                .supportsVision(false).supportsFileInput(false).build());
    }

    @AfterEach
    void tearDown() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, TEXT_ONLY);
        model.close();
    }

    @Test
    void aWarningSaysWhenTheModelCouldNotTakeTheAttachments() throws InterruptedException {
        AgentLoop loop = model.loop().workspace(new InMemoryWorkspace()).build();
        model.respond(request -> text("I can't see the image."));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, LoopRequest.builder()
                .prompt("What's in this photo?")
                .scope(Scope.of("acme", "alice", "s1"))
                .agentProfile(RECURSIVE)
                .attachments(List.of(InputFile.of("photo.png", PNG)))
                .routerConfig(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, TEXT_ONLY))).build()));

        assertThat(model.requests.getFirst().getAttachments()).isEmpty();
        assertThat(capture.messages()).filteredOn(m -> m.type() == MessageType.WARNING)
                .singleElement()
                .satisfies(m -> assertThat(m.message()).contains("anthropic/" + TEXT_ONLY, "can't take", "saved in the workspace"));
    }

    @Test
    void noWarningWhenTheModelTakesThem() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        model.respond(request -> text("A chart."));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, LoopRequest.builder()
                .prompt("What's this?").agentProfile(RECURSIVE)
                .attachments(List.of(InputFile.of("photo.png", PNG))));

        assertThat(model.requests.getFirst().getAttachments()).hasSize(1);
        assertThat(capture.messages()).noneMatch(m -> m.type() == MessageType.WARNING);
    }
}
