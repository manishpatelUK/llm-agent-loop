package io.github.manishpateluk.llmagentloop.search;

/** When workspace files are indexed for {@code knowledge_search}. */
public enum WorkspaceIndexing {

    /** Never: {@code knowledge_search} only finds what's indexed by {@code SemanticSearch.reindex}. */
    NONE,

    /**
     * As each file is written or deleted through an agent, before the write returns (the default).
     * Searches always see the latest files; writes take as long as embedding the file.
     */
    ON_WRITE,

    /**
     * As each file is written or deleted, on a background thread (in order). Writes return at once;
     * a search straight after a write may not see it yet.
     */
    ON_WRITE_BACKGROUND,

    /**
     * Lazily: each search first indexes any file that's new or changed since it was last indexed,
     * and forgets deleted ones. Also catches files written outside the agent (e.g. by your own code
     * straight into the {@code Workspace}); the first search after many changes is slower.
     */
    ON_SEARCH
}
