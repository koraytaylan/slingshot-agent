// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Makes one page, in one commit.
 *
 * <p>Built through the caller's own session rather than through the platform's page manager, and
 * that is a real difference worth stating: the page manager copies a template's initial content and
 * announces the page to whatever is listening, and neither happens here. What this makes is a page
 * node carrying its template and its title, which is what the answer claims and nothing more.</p>
 *
 * <p>The template is checked before anything is written, and a template that does not resolve is
 * told apart from one that resolves and is not a template. The first is usually a typo, which
 * somebody retypes; the second is usually a misunderstanding about what a template is, which
 * somebody has to go and read about. Reporting both the same way sends half of them to the wrong
 * place.</p>
 */
public final class CreatePageHandler implements CommandHandler {

    /** The type a page's own node has. */
    public static final String PAGE_TYPE = ListChildPagesHandler.PAGE_TYPE;

    /** The type a template's own node has. */
    public static final String TEMPLATE_TYPE = "cq:Template";

    /** The property a page records its template in. */
    public static final String TEMPLATE_PROPERTY = "cq:template";

    /** The category a page already at the target address is refused under. */
    public static final String TARGET_ALREADY_EXISTS = "target_already_exists";

    /** The category a parent nothing is at is refused under. */
    public static final String PARENT_NOT_FOUND = "parent_not_found";

    /** The category a parent the caller may not write to is refused under. */
    public static final String PARENT_ACCESS_DENIED = "parent_access_denied";

    /** The category a template nothing is at is refused under. */
    public static final String TEMPLATE_NOT_FOUND = "template_not_found";

    /** The category something that is there and is not a template is refused under. */
    public static final String TEMPLATE_INVALID = "template_invalid";

    /** The category an initial property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public CreatePageHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final CreatePageCommand.Outcome asked = CreatePageCommand.of(arguments, contract);
        if (asked instanceof final CreatePageCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                session -> created(((CreatePageCommand.Held) asked).command(), session)),
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * <p>An initial property this contract will not write is its own category, because the caller's
     * next step differs: they are fixing a value rather than fixing where the page goes.</p>
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(CreatePageCommand.Refusal refusal) {
        return switch (refusal) {
            case PROPERTIES_REJECTED, REMOVAL_ON_CREATION -> PROPERTY_REJECTED;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH,
                    NAME_REJECTED, TITLE_TOO_LONG -> PARENT_NOT_FOUND;
        };
    }


    private static MutationOutcome created(CreatePageCommand command, ResourceResolver session) {
        final Resource parent = session.getResource(command.parentPath());
        if (parent == null) {
            return new MutationOutcome.Refused(PARENT_NOT_FOUND, command.parentPath() + " is not a"
                    + " path this caller can reach, which is the same answer as nothing being"
                    + " there");
        }
        if (session.getResource(command.targetPath()) != null) {
            return new MutationOutcome.Refused(TARGET_ALREADY_EXISTS, command.targetPath()
                    + " is already there, and this command replaces nothing");
        }
        final MutationOutcome template = templated(command, session);
        if (template instanceof MutationOutcome.Refused) {
            return template;
        }
        return written(command, parent, session);
    }

    private static MutationOutcome templated(CreatePageCommand command, ResourceResolver session) {
        final Resource template = session.getResource(command.templatePath());
        if (template == null) {
            return new MutationOutcome.Refused(TEMPLATE_NOT_FOUND, command.templatePath()
                    + " is not there. A page made without its template renders as nothing, so this"
                    + " is refused rather than made untyped.");
        }
        if (!TEMPLATE_TYPE.equals(String.valueOf(template.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(TEMPLATE_INVALID, command.templatePath() + " is"
                    + " there and is not a template; what is there is something else");
        }
        return new MutationOutcome.Changed(CreatePageResult.documentOf(command.targetPath()));
    }

    private static MutationOutcome written(CreatePageCommand command, Resource parent,
                                           ResourceResolver session) {
        try {
            final Resource page = session.create(parent, command.pageName(),
                    Map.of(ListChildPagesHandler.TYPE_PROPERTY, PAGE_TYPE));
            session.create(page, ListChildPagesHandler.PAGE_CONTENT, content(command));
            session.commit();
            return new MutationOutcome.Changed(CreatePageResult.documentOf(page.getPath()));
        } catch (final PersistenceException refused) {
            // A commit that came back refused did not happen. One that never came back at all is
            // the third answer, and the repository tells those apart by throwing or not.
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this page: " + refused.getMessage());
        }
    }

    /**
     * What a new page's content node holds: its type, its template, its title, and what was asked.
     *
     * @param command what was asked
     * @return the properties to write
     */
    private static Map<String, Object> content(CreatePageCommand command) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put(ListChildPagesHandler.TYPE_PROPERTY, "cq:PageContent");
        content.put(TEMPLATE_PROPERTY, command.templatePath());
        content.put(ListChildPagesHandler.TITLE_PROPERTY, command.title());
        command.initialProperties().set()
                .forEach((name, value) -> content.put(name, value.stored()));
        return content;
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
        return List.of(TARGET_ALREADY_EXISTS, PARENT_NOT_FOUND, PARENT_ACCESS_DENIED,
                TEMPLATE_NOT_FOUND, TEMPLATE_INVALID, PROPERTY_REJECTED, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }
}
