// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.List;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.DeletedResourceResult;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.ReferencePolicy;
import rs.slingshot.agent.command.mutation.RepositoryReach;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Removes one page and everything under it, in one commit.
 *
 * <p>The subtree is counted before anything is removed. A subtree past the contract's bound is
 * refused with nothing gone, so the answer is never "most of your page is removed" — which is the
 * state nobody can reason about afterwards and the one an interrupted delete would otherwise
 * leave.</p>
 *
 * <p>Under the refusing policy the references that stopped it are counted and the delete is
 * refused. Which references those are is a question this command deliberately does not answer: a
 * caller who needs the list asks the command whose whole job is finding references, under its own
 * bound and its own permissions.</p>
 */
public final class DeletePageHandler implements CommandHandler {


    /** The category a page nothing is at is refused under. */
    public static final String TARGET_NOT_FOUND = "target_not_found";

    /** The category a page the caller may not remove is refused under. */
    public static final String TARGET_ACCESS_DENIED = "target_access_denied";

    /** The category something that is there and is not a page is refused under. */
    public static final String TARGET_NOT_A_PAGE = "target_not_a_page";

    /** The category a page something points at is refused under, where the policy refuses. */
    public static final String TARGET_IS_REFERENCED = "target_is_referenced";

    /** The category a subtree larger than the contract allows is refused under. */
    public static final String BUDGET_EXCEEDED = "deletion_budget_exceeded";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract, which bounds how much one delete may remove
     */
    public DeletePageHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final DeletePageCommand.Outcome asked = DeletePageCommand.of(arguments, contract);
        if (asked instanceof final DeletePageCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                session -> removed(((DeletePageCommand.Held) asked).command(), session,
                        contract.value(ContractLimit.MAXIMUM_DELETED_NODES), context)),
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(DeletePageCommand.Refusal refusal) {
        return switch (refusal) {
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH,
                    UNKNOWN_REFERENCE_POLICY -> TARGET_NOT_FOUND;
        };
    }

    private static MutationOutcome removed(DeletePageCommand command, ResourceResolver session,
                                           long bound, CallerContext context) {
        final Resource page = session.getResource(command.pagePath());
        if (page == null) {
            return new MutationOutcome.Refused(TARGET_NOT_FOUND, command.pagePath() + " is not"
                    + " there. A caller told a delete succeeded believes something is gone that is"
                    + " not, so an absent target is a refusal rather than nothing to do.");
        }
        if (!ListChildPagesHandler.PAGE_TYPE.equals(String.valueOf(page.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(TARGET_NOT_A_PAGE, command.pagePath() + " is there"
                    + " and is not a page; what is there is something else");
        }
        final List<String> subtree = RepositoryReach.under(page, bound);
        if (subtree.size() > bound) {
            return new MutationOutcome.Refused(BUDGET_EXCEEDED, "this page holds more than the "
                    + bound + " nodes one delete may remove, and it is refused with nothing"
                    + " removed rather than emptied part way");
        }
        return referenced(command, page, subtree, session, context);
    }

    private static MutationOutcome referenced(DeletePageCommand command, Resource page,
                                              List<String> subtree, ResourceResolver session,
                                              CallerContext context) {
        if (command.referencePolicy() == ReferencePolicy.REFUSE_WHEN_REFERENCED
                && !RepositoryReach.pointingAt(session, command.pagePath(),
                        context.discovery().limit()).isEmpty()) {
            return new MutationOutcome.Refused(TARGET_IS_REFERENCED, command.pagePath() + " is"
                    + " referenced, and this request asked to be refused when it is. Which"
                    + " references those are is its own command's question, under its own bound.");
        }
        try {
            session.delete(page);
            session.commit();
            return new MutationOutcome.Changed(
                    DeletedResourceResult.documentOf(command.pagePath(), subtree.size()));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this delete: " + refused.getMessage());
        }
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
        return List.of(TARGET_NOT_FOUND, TARGET_ACCESS_DENIED, TARGET_NOT_A_PAGE,
                TARGET_IS_REFERENCED, BUDGET_EXCEEDED, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }
}
