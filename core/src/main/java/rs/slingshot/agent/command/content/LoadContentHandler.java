// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Reads one subtree as the caller, to exactly the depth they asked for.
 *
 * <p>It obtains nothing. The resolver it is handed is the requesting user's own, wrapped so a write
 * is refused, and every node it reaches is reached through that — so "this command sees what the
 * caller sees, and replaces nothing they own" is a property of what it was given rather than of
 * what it remembered not to do.</p>
 *
 * <p>Content the caller cannot see is not there as far as this is concerned. A path they may not
 * read is answered as absent rather than as forbidden, because the two answers together tell a
 * caller whether something exists at a path they have no right to look at, and that is a
 * disclosure the repository's own permissions were meant to prevent.</p>
 */
public final class LoadContentHandler implements CommandHandler {

    /** Holds one handler, which carries nothing between the commands it runs. */
    public LoadContentHandler() {
        // A handler is state-free on purpose: everything one run may use arrives as an argument to
        // run, so two callers running this command at once cannot reach anything of each other's.
    }

    /** The category a path nobody can see, or nothing is at, is refused under. */
    public static final String NOT_FOUND = "not_found";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    /** The category a caller the repository refused is reported under. */
    public static final String ACCESS_DENIED = "access_denied";

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final LoadContentCommand.Outcome asked =
                LoadContentCommand.of(arguments, context.discovery().limit());
        if (asked instanceof final LoadContentCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return loaded(((LoadContentCommand.Held) asked).command(), resolver, context);
    }

    private Answer loaded(LoadContentCommand command, ResourceResolver resolver,
                          CallerContext context) {
        final Resource resource = resolver.getResource(command.repositoryPath());
        if (resource == null) {
            return new Failed(NOT_FOUND, command.repositoryPath() + " is not a path this caller"
                    + " can read, which is the same answer as nothing being there");
        }
        final javax.jcr.Node node = resource.adaptTo(javax.jcr.Node.class);
        if (node == null) {
            return new Failed(NOT_FOUND, command.repositoryPath() + " is not a repository node");
        }
        try {
            return answered(LoadContentResult.of(node, command.depth(),
                    context.discovery().limit()), command);
        } catch (final RepositoryException failure) {
            return whenTheRepositoryFails(failure, command.repositoryPath());
        }
    }

    /**
     * What a caller is told when the repository fails part way through a subtree.
     *
     * <p>Kept apart from the reading so that what it decides can be proved without a repository
     * that fails on demand. There are two answers and the difference matters to the caller: being
     * refused is something they can do something about, and everything else is not.</p>
     *
     * @param failure what the repository threw
     * @param repositoryPath the subtree being read
     * @return the failure a caller receives
     */
    public static Failed whenTheRepositoryFails(RepositoryException failure,
                                                String repositoryPath) {
        return failure instanceof javax.jcr.AccessDeniedException
                ? new Failed(ACCESS_DENIED, "the repository refused this caller part way through"
                        + " the subtree at " + repositoryPath)
                : new Failed(NOT_FOUND, "the subtree at " + repositoryPath
                        + " could not be read: " + failure.getClass().getSimpleName());
    }

    private static Answer answered(LoadContentResult.Outcome rendered,
                                   LoadContentCommand command) {
        if (rendered instanceof final LoadContentResult.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        return new Produced(LoadContentResult.documentOf(
                (LoadContentResult.Rendered) rendered, command.repositoryPath()));
    }

    @Override
    public List<String> categories() {
        return List.of(ACCESS_DENIED, LoadContentResult.BUDGET_EXCEEDED, NOT_FOUND,
                LoadContentResult.UNSUPPORTED_VALUE);
    }
}
