package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.search.KnowledgeSearch;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.List;
import java.util.Locale;

/**
 * {@code knowledge_search}: finds passages in the user's workspace files by meaning rather than
 * exact words — including inside PDFs, Word and PowerPoint documents. Needs
 * {@code AgentLoop.builder().semanticSearch(...)} (and a workspace) configured. Register with
 * {@code registry.registerAll(KnowledgeTools.all())}, or use {@code Skills.knowledge()}.
 */
public final class KnowledgeTools {

    public static final String SEARCH = "knowledge_search";

    static final int DEFAULT_RESULTS = 5;
    static final int MAX_RESULTS = 20;

    private KnowledgeTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(search());
    }

    public static RegisteredTool search() {
        return new RegisteredTool(ToolDefinition.builder()
                .name(SEARCH)
                .description("Search the user's workspace files (including PDFs, Word and PowerPoint documents) by meaning. "
                        + "Returns the most relevant passages with their file paths. Use it to find information when you "
                        + "don't know which file holds it or the exact words used; use workspace_search for exact text.")
                .parameters(ToolSchemas.object(List.of("query"),
                        "query", ToolSchemas.string("What you're looking for, in natural language, e.g. \"notice period for termination\"."),
                        "limit", ToolSchemas.integer("How many passages to return, up to " + MAX_RESULTS + ". Defaults to "
                                + DEFAULT_RESULTS + ".")))
                .build(),
                (args, context) -> {
                    List<KnowledgeSearch.Hit> hits = context.knowledge().search(ToolArguments.requireString(args, "query"),
                            ToolArguments.optionalInt(args, "limit", DEFAULT_RESULTS, 1, MAX_RESULTS));
                    if (hits.isEmpty()) {
                        return "No matching passages. (Files are searchable once indexed; try workspace_search for exact text.)";
                    }
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < hits.size(); i++) {
                        KnowledgeSearch.Hit hit = hits.get(i);
                        out.append(i + 1).append(". ").append(hit.source())
                                .append(String.format(Locale.ROOT, " (relevance %.2f)", hit.score())).append('\n')
                                .append(hit.text()).append("\n\n");
                    }
                    return out.toString().strip();
                }).withUntrustedOutput();
    }
}
