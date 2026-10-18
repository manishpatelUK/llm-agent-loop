package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns an answer's text into schema data through the steps of an {@link AnswerFormatting}, stopping at the first
 * that gives usable data. Shared by a run's own conversion and {@link AgentLoop#formatAnswer}.
 */
final class AnswerFormatter {

    /** What the caller hears about each step: every reply (to meter), and each failed step with why. */
    interface Listener {
        void onResponse(Response response);

        void onFailedStep(AnswerFormatting.Step step, String reason, AnswerFormatting.Step next);
    }

    private AnswerFormatter() {
    }

    /**
     * The data, or {@code null} when every step failed. {@code beforeEachStep} runs before each call, e.g. to stop
     * a cancelled run (it may throw).
     */
    static Map<String, Object> format(LlmRouter router, String answer, Map<String, Object> schema, RouterConfig run,
            AnswerFormatting formatting, int maxOutputTokens, Runnable beforeEachStep, Listener listener) {
        var steps = formatting.steps();
        for (int i = 0; i < steps.size(); i++) {
            AnswerFormatting.Step step = steps.get(i);
            AnswerFormatting.Step next = i + 1 < steps.size() ? steps.get(i + 1) : null;
            beforeEachStep.run();
            Request request = Request.builder()
                    .prompt("Express the answer below as JSON matching the response schema. Use only information "
                            + "in the answer; use null for anything it doesn't say.\n\nAnswer:\n"
                            + (answer == null ? "" : answer))
                    .responseSchema(schema)
                    .config(AnswerFormatting.configFor(step, run))
                    .maxOutputTokens(maxOutputTokens)
                    .build();
            Response response;
            try {
                response = router.complete(request);
            } catch (RuntimeException e) {
                listener.onFailedStep(step, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        next);
                continue;
            }
            listener.onResponse(response);
            if (response.getStructuredOutput() != null) {
                return response.getStructuredOutput();
            }
            listener.onFailedStep(step, unusable(response, maxOutputTokens), next);
        }
        return null;
    }

    /** Why a structured reply can't be used: cut off at {@code maxOutputTokens}, or simply no usable output. */
    static String unusable(Response response, int maxOutputTokens) {
        Usage usage = response.getUsage();
        return usage != null && usage.getOutputTokens() >= maxOutputTokens
                ? "the reply was cut off at its " + maxOutputTokens + "-token output limit"
                : "the reply had no usable structured output";
    }

    /** How a step reads in a warning. */
    static String describe(AnswerFormatting.Step step) {
        return switch (step) {
            case AS_RUN -> "the run's own model";
            case LOW -> "low thinking";
            case CHEAPER_MODEL -> "a cheaper model";
        };
    }

    /** A {@link Listener} that only passes each reply on. */
    static Listener metering(Consumer<Response> onResponse) {
        return new Listener() {
            @Override
            public void onResponse(Response response) {
                onResponse.accept(response);
            }

            @Override
            public void onFailedStep(AnswerFormatting.Step step, String reason, AnswerFormatting.Step next) {
            }
        };
    }
}
