// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.RepositoryReach;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.ContentAdmission;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Offers one address, or one address and everything beneath it, to the replication service.
 *
 * <p>This is the mutation that commits nothing. What it changes is a queue the platform owns, not
 * the caller's repository, and its registry row says so by declaring the admission's unknown
 * outcome rather than the repository mutation's — which is what the one-commit wrapper reads to
 * decide it owes none.</p>
 *
 * <p>Candidates are gathered through the caller's own session, so a caller offers exactly what they
 * can read and no more. A subtree they can only partly read is offered in part, and the count that
 * comes back is the count of what was admitted rather than of what they asked about — which is the
 * honest number, and occasionally a surprising one.</p>
 */
public final class ReplicateContentHandler implements CommandHandler {

    /** The category a source nothing is at is refused under. */
    public static final String SOURCE_NOT_FOUND = "source_not_found";

    /** The category a source the caller may not read is refused under. */
    public static final String SOURCE_ACCESS_DENIED = "source_access_denied";

    /** The category more candidates than one offer may carry is refused under. */
    public static final String CANDIDATE_LIMIT_EXCEEDED = "candidate_limit_exceeded";

    /** The category a subtree too large to walk at all is refused under. */
    public static final String TRAVERSAL_BUDGET_EXCEEDED = "traversal_budget_exceeded";

    /** The category the platform refusing an offer is reported under. */
    public static final String ADMISSION_REJECTED = "admission_rejected";

    /** The category the platform's own admission bound is reported under. */
    public static final String ADMISSION_BUDGET_EXCEEDED = "admission_budget_exceeded";

    private final AgentContract contract;
    private final ContentAdmission admission;

    /**
     * Holds one handler bound to the contract its bounds come from and the service it offers to.
     *
     * @param contract the authenticated contract, which bounds how many items one offer carries
     * @param admission what hands the addresses to the platform
     */
    public ReplicateContentHandler(AgentContract contract, ContentAdmission admission) {
        this.contract = contract;
        this.admission = admission;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ReplicateContentCommand.Outcome asked =
                ReplicateContentCommand.of(arguments, contract);
        if (asked instanceof final ReplicateContentCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        final ReplicateContentCommand command = ((ReplicateContentCommand.Held) asked).command();
        final Bounds bounds = new Bounds(
                contract.value(ContractLimit.MAXIMUM_REPLICATION_CANDIDATE_PATHS),
                context.discovery().limit());
        return MutationAnswer.of(
                SingleCommit.around(SingleCommit.Expectation.NO_COMMIT, resolver,
                        session -> offered(command, session, bounds, admission)),
                ADMISSION_REJECTED, SingleCommit.ADMISSION_OUTCOME_UNKNOWN);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(ReplicateContentCommand.Refusal refusal) {
        return switch (refusal) {
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH,
                    SCOPE_REJECTED -> SOURCE_NOT_FOUND;
        };
    }

    /**
     * The two numbers a walk is held to.
     *
     * @param candidates how many items one offer may carry, which the contract states
     * @param traversal how many nodes this caller may visit, which their own budget states
     */
    private record Bounds(long candidates, long traversal) {

        /** The point a walk stops at, which is one past the smaller of the two. */
        long walkTo() {
            return Math.min(candidates, traversal);
        }
    }

    /**
     * Which bound a gathered candidate set went past, where it went past one.
     *
     * <p>Told apart because the two mean different things to whoever reads the failure. Past the
     * traversal budget is a subtree this side would not enumerate at all, and the answer is to
     * offer a smaller root. Past the candidate limit is a subtree that was enumerated and holds
     * more items than one offer carries, and the answer is to offer it in parts.</p>
     *
     * @param found how many were gathered, which the walk stopped one past the smaller bound
     * @param candidates how many items one offer may carry
     * @param traversal how many nodes this caller may visit
     * @return the category, or nothing where the set is within both
     */
    public static Optional<String> budgetRefusal(long found, long candidates, long traversal) {
        if (found > traversal) {
            return Optional.of(TRAVERSAL_BUDGET_EXCEEDED);
        }
        return found > candidates ? Optional.of(CANDIDATE_LIMIT_EXCEEDED) : Optional.empty();
    }

    private static MutationOutcome offered(ReplicateContentCommand command, ResourceResolver session,
                                           Bounds bounds, ContentAdmission admission) {
        final Resource source = session.getResource(command.path());
        if (source == null) {
            return new MutationOutcome.Refused(SOURCE_NOT_FOUND, command.path() + " is not a path"
                    + " this caller can reach, which is the same answer as nothing being there");
        }
        final List<String> candidates = command.scope() == SubtreeScope.ITEM_AND_DESCENDANTS
                ? RepositoryReach.under(source, bounds.walkTo())
                : List.of(source.getPath());
        final Optional<String> refused =
                budgetRefusal(candidates.size(), bounds.candidates(), bounds.traversal());
        if (refused.isPresent()) {
            return new MutationOutcome.Refused(refused.orElseThrow(), command.path() + " holds"
                    + " more than this offer carries. Nothing was offered, because half a subtree"
                    + " in a publish queue is a site that renders half old and half new.");
        }
        return answered(admission.offer(candidates, session), candidates.size());
    }

    /**
     * What the platform said, turned into this command's own answer.
     *
     * <p>The unknown outcome stays unknown rather than becoming a failure. The offer may well have
     * been taken, and a caller told it failed offers the same subtree again — which is not harmful
     * in a queue, but it is a false thing to have told them.</p>
     *
     * @param outcome what the platform said
     * @param offered how many were handed over
     * @return this command's answer
     */
    private static MutationOutcome answered(ContentAdmission.Outcome outcome, long offered) {
        return switch (outcome) {
            case ContentAdmission.Admitted admitted -> new MutationOutcome.Changed(
                    ReplicateContentResult.documentOf(admitted.acceptedItemCount()));
            case ContentAdmission.Rejected rejected -> new MutationOutcome.Refused(
                    ADMISSION_REJECTED, "the replication service refused all " + offered
                            + " of them: " + rejected.detail());
            case ContentAdmission.Unknown unknown -> new MutationOutcome.Unknown(
                    "the replication service was offered " + offered + " items and this process"
                            + " never learned what it did with them: " + unknown.detail());
        };
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
        return List.of(SOURCE_NOT_FOUND, SOURCE_ACCESS_DENIED, CANDIDATE_LIMIT_EXCEEDED,
                TRAVERSAL_BUDGET_EXCEEDED, ADMISSION_REJECTED, ADMISSION_BUDGET_EXCEEDED,
                SingleCommit.ADMISSION_OUTCOME_UNKNOWN);
    }
}
