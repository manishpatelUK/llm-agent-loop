package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Optional;

/**
 * Saved {@link Procedure}s, partitioned by {@link Scope} and versioned by name: implement this on
 * your own database. {@link InMemoryProcedureStore} is the ready-made option for development and
 * tests.
 *
 * <p>Like {@code MemoryStore}, every call receives the scope already reduced to the level the
 * procedure tools were set up with ({@code ScopeLevel.TENANT} by default), so an implementation
 * just stores and looks up data under the {@code scope} it's given — {@code scope.key()} works as
 * a column or document field. Calls come from runs' virtual threads (blocking I/O is fine) and
 * must be safe for concurrent use.
 *
 * <p>Saving never overwrites: a name that's already saved gets the next version, and earlier
 * versions stay readable, so a repair can be compared with — or rolled back to — what came before.
 * {@link Procedure#toJson()} and {@link Procedure#fromJson} turn a procedure into one document.
 */
public interface ProcedureStore {

    /**
     * Stores {@code procedure} as the next version of its name in {@code scope}: 1 if it's new,
     * otherwise one more than the latest. Returns it with that version and the time it was saved
     * ({@link Procedure#withVersion}). Two concurrent saves of one name must get different versions
     * (e.g. a unique index on scope, name and version, retrying on a clash).
     */
    Procedure save(Scope scope, Procedure procedure);

    /** The latest version of {@code name} in {@code scope}. */
    Optional<Procedure> latest(Scope scope, String name);

    /** Version {@code version} of {@code name} in {@code scope}. */
    Optional<Procedure> version(Scope scope, String name, int version);

    /** Every version of {@code name} in {@code scope}, oldest first; empty if there's none. */
    List<Procedure> versions(Scope scope, String name);

    /**
     * Up to {@code limit} procedures in {@code scope} (the latest version of each) most relevant to
     * {@code query}, best first, matching their names and descriptions. A blank {@code query} means
     * most recently saved first.
     */
    List<Procedure> search(Scope scope, String query, int limit);

    /** Removes every version of {@code name} from {@code scope}; {@code false} if there was none. Not offered to the model. */
    boolean delete(Scope scope, String name);
}
