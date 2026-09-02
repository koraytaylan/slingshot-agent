// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.List;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.mutation.MoveRequest;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.RepositoryReach;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Moves one page, and the links that point at it if the caller asked for that, in one commit.
 *
 * <p>The references are counted before the move rather than adjusted as they are found. A move that
 * rewrote four hundred links and then hit the bound would leave a repository half pointing at each
 * address, and there is no answer to give a caller about that state. Counting first means the
 * refusal happens before anything moved, which is what one-commit atomicity is for.</p>
 */
public final class MovePageHandler implements CommandHandler {

    /** The category a source nothing is at is refused under. */
    public static final String SOURCE_NOT_FOUND = "source_not_found";

    /** The category a source the caller may not move is refused under. */
    public static final String SOURCE_ACCESS_DENIED = "source_access_denied";

    /** The category a destination whose parent is not there is refused under. */
    public static final String DESTINATION_PARENT_NOT_FOUND = "destination_parent_not_found";

    /** The category a destination something is already at is refused under. */
    public static final String DESTINATION_ALREADY_EXISTS = "destination_already_exists";

    /** The category a destination inside the source is refused under. */
    public static final String DESTINATION_INSIDE_SOURCE = "destination_inside_source";

    /** The category a move with more references than may be adjusted is refused under. */
    public static final String ADJUSTMENT_BUDGET_EXCEEDED = "reference_adjustment_budget_exceeded";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract, which bounds how many links one move adjusts
     */
    public MovePageHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final MoveRequest.Outcome asked = MovePageCommand.of(arguments, contract);
        if (asked instanceof final MoveRequest.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                session -> moved(((MoveRequest.Held) asked).command(), session,
                        contract.value(ContractLimit.MAXIMUM_ADJUSTED_REFERENCES), context)),
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * <p>A destination inside the source has its own category because it is the mistake with the
     * most confusing aftermath, and a caller who made it is not fixing a path — they are rethinking
     * where the page should go.</p>
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(MoveRequest.Refusal refusal) {
        return switch (refusal) {
            case DESTINATION_INSIDE_SOURCE -> DESTINATION_INSIDE_SOURCE;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH,
                    ADJUSTMENT_NOT_A_FLAG -> SOURCE_NOT_FOUND;
        };
    }

    private static MutationOutcome moved(MoveRequest command, ResourceResolver session,
                                         long bound, CallerContext context) {
        final Resource source = session.getResource(command.sourcePath());
        if (source == null) {
            return new MutationOutcome.Refused(SOURCE_NOT_FOUND, command.sourcePath() + " is not"
                    + " there, so there is nothing to move");
        }
        if (session.getResource(command.destinationPath()) != null) {
            return new MutationOutcome.Refused(DESTINATION_ALREADY_EXISTS,
                    command.destinationPath() + " is already taken, and this command replaces"
                            + " nothing");
        }
        final int lastSlash = command.destinationPath().lastIndexOf('/');
        final String parent = lastSlash <= 0 ? "/" : command.destinationPath()
                .substring(0, lastSlash);
        if (session.getResource(parent) == null) {
            return new MutationOutcome.Refused(DESTINATION_PARENT_NOT_FOUND, parent + " is not"
                    + " there, so there is nowhere to move this page to");
        }
        // The platform moves a page under a new parent and keeps its own name; renaming it in the
        // same step is a second operation this build does not make. Checked before anything is
        // staged, because the alternative is a page that lands one address away from the one the
        // caller asked for and an answer that says it went where they asked.
        if (!nameOf(command.destinationPath()).equals(nameOf(command.sourcePath()))) {
            return new MutationOutcome.Refused(COMMIT_FAILED, "this move renames the page as well"
                    + " as moving it, from " + nameOf(command.sourcePath()) + " to "
                    + nameOf(command.destinationPath()) + ", and this build moves a page under a"
                    + " new parent without renaming it. Nothing was changed.");
        }
        return adjusted(command, session, bound, context);
    }

    private static MutationOutcome adjusted(MoveRequest command, ResourceResolver session,
                                            long bound, CallerContext context) {
        final List<Resource> pointing = command.adjustReferences()
                == MoveRequest.ReferenceAdjustment.FOLLOWED
                ? RepositoryReach.pointingAt(session, command.sourcePath(),
                        context.discovery().limit()) : List.of();
        if (pointing.size() > bound) {
            return new MutationOutcome.Refused(ADJUSTMENT_BUDGET_EXCEEDED, pointing.size()
                    + " references is more than the " + bound + " one move may adjust. It is"
                    + " refused before the move rather than after some of them, because half your"
                    + " links pointing at each address is a state nobody can reason about.");
        }
        try {
            session.move(command.sourcePath(), parentOf(command.destinationPath()));
            final long moved = RepositoryReach.repointed(pointing, command.sourcePath(),
                    command.destinationPath());
            session.commit();
            return new MutationOutcome.Changed(MovePageResult.documentOf(command.sourcePath(),
                    command.destinationPath(), moved));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this move: " + refused.getMessage());
        }
    }

    private static String parentOf(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
    }

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }



    @Override
    public List<String> categories() {
        return declaredCategories();
    }

    /**
     * Everything this command can fail with, which its registry row declares exactly.
     *
     * @return the categories
     */
    public static List<String> declaredCategories() {
        return List.of(SOURCE_NOT_FOUND, SOURCE_ACCESS_DENIED, DESTINATION_PARENT_NOT_FOUND,
                DESTINATION_ALREADY_EXISTS, DESTINATION_INSIDE_SOURCE, ADJUSTMENT_BUDGET_EXCEEDED,
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }
}
