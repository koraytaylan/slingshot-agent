// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One query, written down before it is issued.
 *
 * <p>Adobe Experience Manager answers a query either from an index or by walking the repository,
 * and walking it is how a single command takes an author instance down. A query that exists only
 * as a string a handler builds is a query nobody can check, so every one is declared as data — its
 * statement shape, the roots it is restricted to, and the properties it filters on — and a query
 * issued that nobody declared is refused rather than run.</p>
 *
 * <p>Two checks, and both are needed. The build compares every declared query with the indexes a
 * deployment row was declared to provide; run time compares it with the plan the instance in front
 * of it actually returns, before a node is examined. A customer can remove an index and a Cloud
 * Service environment's index set is theirs to change, so the second is the only one their author
 * is protected by — and a disagreement between the two is a deployment whose indexes are not what
 * this build was told, reported as itself.</p>
 *
 * @param name what this query is called, which is what a refusal names
 * @param statement the shape of the statement it issues, with its arguments left as placeholders
 * @param roots the subtrees it is restricted to, of which there is at least one
 * @param properties the properties it filters on, which is what an index has to cover
 * @param issuedBy the command that issues it
 */
public record DeclaredQuery(String name, String statement, List<String> roots,
                            List<String> properties, String issuedBy) {

    /** The word a plan uses for the thing this refuses. */
    public static final String TRAVERSAL = "traverse";

    /**
     * Holds a query whose every part is stated.
     *
     * @throws IllegalArgumentException if a part is missing, because a query with no root is one
     *     that starts at the top of somebody's repository
     */
    public DeclaredQuery {
        requireStated(name, "name");
        requireStated(statement, "statement");
        requireStated(issuedBy, "issuing command");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(name + " is restricted to no subtree, which is a"
                    + " query that starts at the top of somebody's repository");
        }
        roots = List.copyOf(roots);
        properties = List.copyOf(properties);
    }

    /** Why a query is not run. */
    public enum Refusal {
        /** Nothing declared it, so nothing has checked it against any index. */
        UNDECLARED,
        /** The instance in front of this one would answer it by walking the repository. */
        WOULD_TRAVERSE,
        /** What was issued is not the shape that was declared. */
        NOT_THE_DECLARED_SHAPE
    }

    /** What asking to run one produced. */
    public sealed interface Outcome permits Permitted, Refused {
    }

    /**
     * A query that may run.
     *
     * @param query the query
     */
    public record Permitted(DeclaredQuery query) implements Outcome {
    }

    /**
     * A query that may not, and exactly why.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether one statement is one this build declared, and whether the plan it would run under
     * walks the repository.
     *
     * <p>The plan is asked for before a node is examined, so a query that would traverse costs
     * nothing rather than costing an instance.</p>
     *
     * @param declared every query this build declares
     * @param issued the statement about to be issued
     * @param plan what the platform says it would do, in the platform's own words
     * @return the query, or the one reason it does not run
     */
    public static Outcome permitted(List<DeclaredQuery> declared, String issued, String plan) {
        final Optional<DeclaredQuery> held = declared.stream()
                .filter(query -> query.statement().equals(issued))
                .findFirst();
        if (held.isEmpty()) {
            return new Refused(Refusal.UNDECLARED, "nothing declares the statement being issued,"
                    + " so nothing has checked it against any index");
        }
        if (plan.toLowerCase(java.util.Locale.ROOT).contains(TRAVERSAL)) {
            return new Refused(Refusal.WOULD_TRAVERSE, held.get().name()
                    + " would be answered by walking the repository on this instance, which is how"
                    + " one command takes an author down: " + plan);
        }
        return new Permitted(held.get());
    }

    /**
     * The one category a refused query is reported as.
     *
     * <p>The discovery budget's own, because running out of rows to examine and being about to
     * examine every row there is are the same problem measured at two moments.</p>
     *
     * @return the category
     */
    public static String category() {
        return Budget.Kind.DISCOVERY.category();
    }

    /**
     * The subtrees this query is restricted to.
     *
     * @return the roots
     */
    @Override
    public List<String> roots() {
        return Collections.unmodifiableList(roots);
    }

    /**
     * The properties this query filters on, which is what an index has to cover.
     *
     * @return the properties
     */
    @Override
    public List<String> properties() {
        return Collections.unmodifiableList(properties);
    }

    private static void requireStated(String value, String part) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("a declared query states no " + part);
        }
    }
}
