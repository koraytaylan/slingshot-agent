// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Changes one page's properties, in one commit.
 *
 * <p>Both lists are applied to the page's content node and committed together, or neither is. A
 * property the repository will not let go of stops the whole update before the commit rather than
 * part way through it — a caller told a removal succeeded will build on that, and the property that
 * silently stayed is the defect that surfaces three commands later, somewhere else.</p>
 *
 * <p>Whether a property can be removed is asked of the repository by trying and undoing rather than
 * by consulting a list of protected names. A list here would be this build's guess at what a
 * particular repository protects, and repositories differ; what does not differ is that the
 * repository itself knows.</p>
 */
public final class UpdatePageHandler implements CommandHandler {

    /** The category a page nothing is at is refused under. */
    public static final String PAGE_NOT_FOUND = "page_not_found";

    /** The category a page the caller may not change is refused under. */
    public static final String PAGE_ACCESS_DENIED = "page_access_denied";

    /** The category something that is there and is not a page is refused under. */
    public static final String PAGE_INVALID = "page_invalid";

    /** The category a property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a property the repository will not let go of is refused under. */
    public static final String PROPERTY_NOT_REMOVABLE = "property_not_removable";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public UpdatePageHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final UpdatePageCommand.Outcome asked = UpdatePageCommand.of(arguments, contract);
        if (asked instanceof final UpdatePageCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                session -> written(((UpdatePageCommand.Held) asked).command(), session)),
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(UpdatePageCommand.Refusal refusal) {
        return switch (refusal) {
            case CHANGE_REJECTED, TITLE_TOO_LONG -> PROPERTY_REJECTED;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH ->
                    PAGE_NOT_FOUND;
        };
    }

    private static MutationOutcome written(UpdatePageCommand command, ResourceResolver session) {
        final Resource page = session.getResource(command.pagePath());
        if (page == null) {
            return new MutationOutcome.Refused(PAGE_NOT_FOUND, command.pagePath() + " is not a"
                    + " path this caller can reach, which is the same answer as nothing being"
                    + " there");
        }
        if (!ListChildPagesHandler.PAGE_TYPE.equals(String.valueOf(page.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(PAGE_INVALID, command.pagePath() + " is there and"
                    + " is not a page; what is there is something else");
        }
        final Resource content = page.getChild(ListChildPagesHandler.PAGE_CONTENT);
        if (content == null) {
            return new MutationOutcome.Refused(PAGE_INVALID, command.pagePath() + " is a page with"
                    + " no content node, which is a repository in a state the platform does not"
                    + " produce");
        }
        return applied(command, content, session);
    }

    private static MutationOutcome applied(UpdatePageCommand command, Resource content,
                                           ResourceResolver session) {
        final ModifiableValueMap values = content.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            return new MutationOutcome.Refused(PAGE_ACCESS_DENIED, command.pagePath() + " is not a"
                    + " page this caller may change");
        }
        final Optional<String> immovable = command.change().immovableIn(values);
        if (immovable.isPresent()) {
            return new MutationOutcome.Refused(PROPERTY_NOT_REMOVABLE, immovable.get() + " is a"
                    + " property this repository will not let go of. The whole update is refused"
                    + " rather than applied without it, because a caller told a removal succeeded"
                    + " will build on that.");
        }
        command.change().set().forEach((name, value) -> values.put(name, value.stored()));
        if (!UpdatePageCommand.TITLE_UNCHANGED.equals(command.title())) {
            values.put(ListChildPagesHandler.TITLE_PROPERTY, command.title());
        }
        return committed(command, session);
    }

    private static MutationOutcome committed(UpdatePageCommand command, ResourceResolver session) {
        try {
            session.commit();
            return new MutationOutcome.Changed(UpdatePageResult.documentOf(command.pagePath()));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this update: " + refused.getMessage());
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
        return List.of(PAGE_NOT_FOUND, PAGE_ACCESS_DENIED, PAGE_INVALID, PROPERTY_REJECTED,
                PROPERTY_NOT_REMOVABLE, COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }
}
