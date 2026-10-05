package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmrouter.model.ToolCall;

/**
 * Sees every tool call an agent makes, before and after it runs — the one place to add policy
 * without the library choosing it for you: require approval for payments, keep an audit log of
 * every action, redact personal data from results, rate-limit an expensive API per tenant.
 * Register with {@code AgentLoop.builder().toolInterceptor(...)}; several run in registration order
 * ({@code before} in order, {@code after} in reverse, like nested middleware).
 *
 * <pre>{@code
 * AgentLoop.builder().toolInterceptor(new ToolInterceptor() {
 *     public ToolDecision before(ToolCall call, ToolContext context) {
 *         if (call.getName().equals("api_request") && "POST".equals(call.getArguments().get("method"))
 *                 && !approvals.ask(context.scope(), call)) {
 *             return ToolDecision.refuse("The user declined this change");
 *         }
 *         return ToolDecision.proceed();
 *     }
 *
 *     public String after(ToolCall call, String result, ToolContext context) {
 *         audit.record(context.scope(), call.getName(), call.getArguments());
 *         return redactor.redact(result);
 *     }
 * })
 * }</pre>
 *
 * <p>Covers registered tools and caller-resolved unregistered ones; not the loop's own control
 * calls ({@code report_complete}, {@code spawn_sub_task}). Runs on the run's thread; blocking (e.g.
 * waiting for an approval) is fine. An exception thrown from an interceptor ends the run like any
 * tool failure — return {@link ToolDecision#refuse} instead to let the model carry on.
 */
public interface ToolInterceptor {

    /** Called before the tool runs. Defaults to {@link ToolDecision#proceed()}. */
    default ToolDecision before(ToolCall call, ToolContext context) {
        return ToolDecision.proceed();
    }

    /** Called with the tool's result (including {@code "Error: ..."} results); returns what the model sees. Defaults to unchanged. */
    default String after(ToolCall call, String result, ToolContext context) {
        return result;
    }

    /** Called when the tool threw something that will end the run — for logging; the failure still propagates. */
    default void failed(ToolCall call, Throwable error, ToolContext context) {
    }
}
