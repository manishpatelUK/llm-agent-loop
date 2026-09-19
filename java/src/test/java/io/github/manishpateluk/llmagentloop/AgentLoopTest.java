package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.LlmRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    private final AgentLoop loop = new AgentLoop(new LlmRouter(List.of()));

    @Test
    void constructorRejectsNullRouter() {
        assertThatThrownBy(() -> new AgentLoop(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void threeArgOverloadDelegatesThroughToTheCanonicalRun() {
        assertThatThrownBy(() -> loop.run("do something", response -> { }, error -> { }))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fourArgOverloadDelegatesThroughToTheCanonicalRun() {
        assertThatThrownBy(() -> loop.run("do something", response -> { }, error -> { }, message -> { }))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shortOverloadsStillValidateRequiredFieldsBeforeExecuting() {
        assertThatThrownBy(() -> loop.run((String) null, response -> { }, error -> { }))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void canonicalRunRejectsNullRequest() {
        assertThatThrownBy(() -> loop.run((LoopRequest) null)).isInstanceOf(NullPointerException.class);
    }
}
