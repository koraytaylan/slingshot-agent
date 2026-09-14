// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.ResultDelivery;

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
                    context.discovery().limit()), command, context);
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
                                   LoadContentCommand command, CallerContext context) {
        if (rendered instanceof final LoadContentResult.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final LoadContentResult.Rendered held = (LoadContentResult.Rendered) rendered;
        if (!held.overflowed(inlineBound())) {
            return new Produced(LoadContentResult.documentOf(held, command.repositoryPath()));
        }
        return overflowed(held, command.repositoryPath());
    }

    /**
     * How large a loaded document may be before it is answered by reference.
     *
     * <p>The command's own bound rather than the contract's general inline bound: the client
     * compares a document against this one exactly, so a document that fitted the larger bound
     * would be refused by the client against the smaller one it actually declared.</p>
     *
     * @return the bound
     */
    private static long inlineBound() {
        final rs.slingshot.agent.contract.AgentContract.Outcome loaded =
                rs.slingshot.agent.contract.AgentContract.load();
        if (!(loaded instanceof final rs.slingshot.agent.contract.AgentContract.Loaded held)) {
            throw new IllegalStateException("no contract: this build cannot read its own bound");
        }
        return held.contract().value(
                rs.slingshot.agent.contract.ContractLimit.MAXIMUM_AGENT_INLINE_LOADED_DOCUMENT_BYTES);
    }

    /**
     * The answer for one document too large to carry.
     *
     * <p>The identifier is the document's own digest. An artifact store assigns identifiers and
     * none is wired to this command, and an identifier the document cannot be checked against is
     * worse than one that is exactly the thing it names.</p>
     *
     * @param rendered the document that did not fit
     * @param repositoryPath the path that was asked for
     * @return the answer
     */
    private static Answer overflowed(LoadContentResult.Rendered rendered, String repositoryPath) {
        final byte[] bytes = rendered.documentBytes();
        final String digest = Digest.of(bytes).rendered();
        final OverflowPublication.Published published = new OverflowPublication.Published(
                LoadContentResult.LOADED_CONTENT_SLOT,
                new ResultDelivery.Artifact(bytes.length,
                        ((DigestValue.Held) DigestValue.of(digest)).digest()));
        return new Artifact(LoadContentResult.artifactOf(repositoryPath, published, digest,
                LoadContentResult.LOADED_CONTENT_MEDIA_TYPE,
                LoadContentResult.LOADED_CONTENT_FILE_NAME), LoadContentResult.LOADED_CONTENT_SLOT,
                bytes);
    }

    @Override
    public List<String> categories() {
        return List.of(ACCESS_DENIED, LoadContentResult.BUDGET_EXCEEDED, NOT_FOUND,
                LoadContentResult.UNSUPPORTED_VALUE);
    }
}
