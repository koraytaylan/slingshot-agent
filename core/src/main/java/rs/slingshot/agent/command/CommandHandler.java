// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One command's own work, and everything it is allowed to reach.
 *
 * <p>One method. A handler is given its arguments and a caller context and answers a result; it
 * takes no session, opens nothing, and has no lifecycle callback it could keep state between
 * invocations in. Everything else in this plan follows from that being true rather than intended:
 * a handler that cannot obtain a session cannot run as anybody but the caller, and a handler with
 * nowhere to keep state cannot be the fortieth one that quietly does.</p>
 *
 * <p>What it may fail with is not its own decision either. The categories are its registry row's,
 * and the correspondence is checked in both directions: a category no handler can produce is as
 * much a defect as one no row declares.</p>
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Runs one command.
     *
     * @param arguments what the caller submitted, already held to the command's own schema
     * @param resolver the requesting user's own resolver — the request's, because a command
     *     executes inside the request that submitted it and there is no later moment at which one
     *     could be obtained for somebody
     * @param context the budgets it runs under and where its progress goes
     * @return what the command produced, or the one category it failed with
     */
    Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver, CallerContext context);

    /** What running one command produced. */
    sealed interface Answer permits Produced, Failed {
    }

    /**
     * A result, which is the command's own document.
     *
     * @param result what it produced
     */
    record Produced(DocumentValue.Mapping result) implements Answer {
    }

    /**
     * A failure, named with one of the categories the command's own row declares.
     *
     * @param category which way it failed
     * @param detail what was observed, which is for this side's own record
     */
    record Failed(String category, String detail) implements Answer {
    }

    /**
     * Every category one handler can produce, which its row is compared against.
     *
     * <p>Declared rather than discovered: a category a handler can produce and no row declares is a
     * failure a client cannot name, and a category a row declares and no handler produces is a row
     * describing a command that does not exist.</p>
     *
     * @return the categories
     */
    default List<String> categories() {
        return List.of();
    }
}
