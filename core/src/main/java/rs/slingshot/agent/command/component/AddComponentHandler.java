// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.content.FindPagesUsingComponentsHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Adds one component to a page, last among its siblings, in one commit.
 *
 * <p>Last rather than anywhere in particular, and a caller who wants it elsewhere reorders it and
 * can see whether that worked. An addition that also placed the component would be two changes
 * reported as one, and the half that failed would be the invisible half.</p>
 *
 * <p>A parent that cannot hold an order is refused as itself. It is a fact about the page an author
 * is working in — some parents keep their children in a stated order and some do not — and telling
 * them that is telling them something they can act on, where a generic refusal is not.</p>
 */
public final class AddComponentHandler implements CommandHandler {

    /** The category a page nothing is at is refused under. */
    public static final String PAGE_NOT_FOUND = "page_not_found";

    /** The category something that is there and is not a page is refused under. */
    public static final String PAGE_INVALID = "page_invalid";

    /** The category a parent nothing is at is refused under. */
    public static final String PARENT_NOT_FOUND = "parent_not_found";

    /** The category a parent the caller may not add to is refused under. */
    public static final String PARENT_ACCESS_DENIED = "parent_access_denied";

    /** The category a parent that cannot hold an order is refused under. */
    public static final String PARENT_NOT_ORDERABLE = "parent_not_orderable";

    /** The category a component already at the target address is refused under. */
    public static final String TARGET_ALREADY_EXISTS = "target_already_exists";

    /** The category a property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    /** The type a node that keeps its children in order has. */
    public static final String ORDERED_TYPE = "nt:unstructured";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public AddComponentHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final AddComponentCommand.Outcome asked = AddComponentCommand.of(arguments, contract);
        if (asked instanceof final AddComponentCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                session -> added(((AddComponentCommand.Held) asked).command(), session)),
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(AddComponentCommand.Refusal refusal) {
        return switch (refusal) {
            case PROPERTIES_REJECTED, RESOURCE_TYPE_REJECTED, NAME_REJECTED -> PROPERTY_REJECTED;
            case PARENT_REJECTED -> PARENT_NOT_FOUND;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH ->
                    PAGE_NOT_FOUND;
        };
    }

    private static MutationOutcome added(AddComponentCommand command, ResourceResolver session) {
        final Resource page = session.getResource(command.pagePath());
        if (page == null) {
            return new MutationOutcome.Refused(PAGE_NOT_FOUND, command.pagePath() + " is not a"
                    + " path this caller can reach, which is the same answer as nothing being"
                    + " there");
        }
        if (!ListChildPagesHandler.PAGE_TYPE.equals(String.valueOf(page.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(PAGE_INVALID, command.pagePath() + " is there and"
                    + " is not a page; a component goes inside a page");
        }
        final String parentPath = ComponentParent.pathOf(command.contentParent(),
                command.pagePath() + "/" + ListChildPagesHandler.PAGE_CONTENT);
        final Resource parent = session.getResource(parentPath);
        if (parent == null) {
            return new MutationOutcome.Refused(PARENT_NOT_FOUND,
                    parentPath + " is not there, so there is nowhere inside this page to add to");
        }
        return ordered(command, parent, parentPath, session);
    }

    private static MutationOutcome ordered(AddComponentCommand command, Resource parent,
                                           String parentPath, ResourceResolver session) {
        if (!ORDERED_TYPE.equals(String.valueOf(parent.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(PARENT_NOT_ORDERABLE, parentPath + " keeps its"
                    + " children in no particular order, so a component added to it would be"
                    + " somewhere this command cannot say and a reorder could not move it");
        }
        final String target = parentPath + "/" + command.componentName();
        if (session.getResource(target) != null) {
            return new MutationOutcome.Refused(TARGET_ALREADY_EXISTS,
                    target + " is already there, and this command replaces nothing");
        }
        try {
            session.create(parent, command.componentName(), properties(command));
            session.commit();
            return new MutationOutcome.Changed(AddComponentResult.documentOf(target));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this component: " + refused.getMessage());
        }
    }

    private static Map<String, Object> properties(AddComponentCommand command) {
        final Map<String, Object> written = new LinkedHashMap<>();
        written.put(ListChildPagesHandler.TYPE_PROPERTY, ORDERED_TYPE);
        written.put(FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY,
                command.resourceType());
        command.properties().set().forEach((name, value) ->
                written.put(name, value.stored()));
        return written;
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
        return List.of(PAGE_NOT_FOUND, PAGE_INVALID, PARENT_NOT_FOUND, PARENT_ACCESS_DENIED,
                PARENT_NOT_ORDERABLE, TARGET_ALREADY_EXISTS, PROPERTY_REJECTED, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }
}
