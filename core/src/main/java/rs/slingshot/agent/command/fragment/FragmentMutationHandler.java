// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sling.api.resource.ModifiableValueMap;
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
import rs.slingshot.agent.command.page.CreatePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The six commands that make, change and remove fragments, each one commit.
 *
 * <p>One handler for six because what they share is what matters: each is refused before anything
 * is written where it cannot proceed, each is held to exactly one commit, and each answers with the
 * address it acted on. Which of the six it is comes from the kind the handler was built for rather
 * than from an argument, so the six keep separate registry rows, failure sets and bounds.</p>
 *
 * <p>Nothing here runs the platform's fragment API. What this writes is the node structure a
 * fragment is made of, which is what the answer claims and nothing more — no workflow is launched,
 * no rendition is made, and nothing is announced to whatever might be listening.</p>
 */
public final class FragmentMutationHandler implements CommandHandler {

    /** Which of the six this handler answers. */
    public enum Kind {
        /** Makes a content fragment from a model. */
        CONTENT_CREATION,
        /** Changes one variation of a content fragment. */
        CONTENT_UPDATE,
        /** Removes a content fragment. */
        CONTENT_REMOVAL,
        /** Makes an experience fragment and its first variation. */
        EXPERIENCE_CREATION,
        /** Changes one variation of an experience fragment. */
        EXPERIENCE_UPDATE,
        /** Removes an experience fragment. */
        EXPERIENCE_REMOVAL
    }

    private final AgentContract contract;
    private final Kind kind;

    /**
     * Holds one handler for one of the six.
     *
     * @param contract the authenticated contract
     * @param kind which of the six commands this handler answers
     */
    public FragmentMutationHandler(AgentContract contract, Kind kind) {
        this.contract = contract;
        this.kind = kind;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case CONTENT_CREATION -> contentCreation(arguments, resolver);
            case CONTENT_UPDATE -> contentUpdate(arguments, resolver);
            case EXPERIENCE_CREATION -> experienceCreation(arguments, resolver);
            case EXPERIENCE_UPDATE -> experienceUpdate(arguments, resolver);
            case CONTENT_REMOVAL, EXPERIENCE_REMOVAL -> removal(arguments, resolver, context);
        };
    }

    private Answer contentCreation(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final CreateContentFragmentCommand.Outcome asked =
                CreateContentFragmentCommand.of(arguments, contract);
        if (asked instanceof final CreateContentFragmentCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session ->
                madeContent(((CreateContentFragmentCommand.Held) asked).command(), session));
    }

    /**
     * Which declared category one content creation refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(CreateContentFragmentCommand.Refusal refusal) {
        return switch (refusal) {
            case ELEMENTS_REJECTED -> FragmentHandlers.ELEMENT_VALUE_REJECTED;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH,
                    NAME_REJECTED, TITLE_TOO_LONG -> FragmentHandlers.PARENT_NOT_FOUND;
        };
    }

    /**
     * Which declared category one content change refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(UpdateContentFragmentCommand.Refusal refusal) {
        return switch (refusal) {
            case ELEMENTS_REJECTED -> FragmentHandlers.ELEMENT_VALUE_REJECTED;
            case VARIATION_NAME_REJECTED -> FragmentHandlers.VARIATION_NOT_FOUND;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH,
                    TITLE_TOO_LONG -> FragmentHandlers.FRAGMENT_NOT_FOUND;
        };
    }

    /**
     * Which declared category one experience change refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(UpdateExperienceFragmentCommand.Refusal refusal) {
        return switch (refusal) {
            case CHANGE_REJECTED, TITLE_TOO_LONG -> FragmentHandlers.PROPERTY_REJECTED;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH ->
                    FragmentHandlers.VARIATION_NOT_FOUND;
        };
    }

    private static MutationOutcome madeContent(CreateContentFragmentCommand command,
                                               ResourceResolver session) {
        final Resource parent = session.getResource(command.parentPath());
        if (parent == null) {
            return new MutationOutcome.Refused(FragmentHandlers.PARENT_NOT_FOUND,
                    command.parentPath() + " is not a path this caller can reach");
        }
        if (session.getResource(command.targetPath()) != null) {
            return new MutationOutcome.Refused(FragmentHandlers.TARGET_ALREADY_EXISTS,
                    command.targetPath() + " is already there, and this command replaces nothing");
        }
        final FragmentModel.Outcome model = FragmentModel.at(session, command.modelPath());
        if (model instanceof final FragmentModel.Missing missing) {
            return new MutationOutcome.Refused(FragmentHandlers.MODEL_NOT_FOUND,
                    missing.modelPath() + " is not there. A fragment made without its model is a"
                            + " node no authoring tool will open, so this is refused rather than"
                            + " made untyped.");
        }
        if (model instanceof final FragmentModel.Invalid invalid) {
            return new MutationOutcome.Refused(FragmentHandlers.MODEL_INVALID,
                    invalid.modelPath() + " " + invalid.detail());
        }
        return declared(command, parent, session, ((FragmentModel.Read) model).model());
    }

    private static MutationOutcome declared(CreateContentFragmentCommand command, Resource parent,
                                            ResourceResolver session, FragmentModel model) {
        final Optional<String> unknown = model.unknownIn(command.elements());
        if (unknown.isPresent()) {
            return new MutationOutcome.Refused(FragmentHandlers.ELEMENT_UNKNOWN, unknown.get()
                    + " is not an element " + model.modelPath() + " declares. It is refused rather"
                    + " than written as a loose property, because a fragment carrying properties"
                    + " outside its model reads back differently through every tool that opens"
                    + " it.");
        }
        try {
            final Resource fragment = session.create(parent, command.name(), Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
            structured(session, fragment, command);
            return sealed(session, FragmentResult.documentOf(command.targetPath()));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(FragmentHandlers.COMMIT_FAILED,
                    "the repository refused this fragment: " + refused.getMessage());
        }
    }

    /**
     * Writes what a content fragment is made of: its content node, its data node, its master.
     *
     * @param session the caller's own session
     * @param fragment the fragment's own node
     * @param command what was asked
     * @throws PersistenceException if the repository refuses any of it
     */
    private static void structured(ResourceResolver session, Resource fragment,
                                   CreateContentFragmentCommand command)
            throws PersistenceException {
        final Map<String, Object> held = new LinkedHashMap<>();
        held.put(ListChildPagesHandler.TYPE_PROPERTY,
                FragmentHandlers.CONTENT_FRAGMENT_CONTENT_TYPE);
        held.put(FragmentHandlers.CONTENT_FRAGMENT_FLAG, Boolean.TRUE);
        if (!CreateContentFragmentCommand.NO_TITLE.equals(command.title())) {
            held.put(ListChildPagesHandler.TITLE_PROPERTY, command.title());
        }
        final Resource content =
                session.create(fragment, ListChildPagesHandler.PAGE_CONTENT, held);
        final Resource data = session.create(content, FragmentHandlers.DATA_NODE, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.UNSTRUCTURED_TYPE,
                FragmentHandlers.MODEL_PROPERTY, command.modelPath()));
        final Map<String, Object> master = new LinkedHashMap<>();
        master.put(ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.UNSTRUCTURED_TYPE);
        command.elements().values().forEach((name, values) -> master.put(name, stored(values)));
        session.create(data, FragmentHandlers.MASTER_VARIATION, master);
    }

    /**
     * How one element's values are held in a repository.
     *
     * <p>One value is one string and several are an array, which is the platform's own distinction
     * rather than this build's: an element declared as one value and stored as an array of one
     * reads back through the authoring tools as a different element.</p>
     *
     * @param values what the caller asked for
     * @return what to store
     */
    private static Object stored(List<String> values) {
        return values.size() == 1 ? values.getFirst() : values.toArray(new String[0]);
    }

    private Answer contentUpdate(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final UpdateContentFragmentCommand.Outcome asked =
                UpdateContentFragmentCommand.of(arguments, contract);
        if (asked instanceof final UpdateContentFragmentCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session ->
                changedContent(((UpdateContentFragmentCommand.Held) asked).command(), session));
    }

    private static MutationOutcome changedContent(UpdateContentFragmentCommand command,
                                                  ResourceResolver session) {
        final Resource fragment = session.getResource(command.fragmentPath());
        if (fragment == null) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_NOT_FOUND,
                    command.fragmentPath() + " is not a path this caller can reach");
        }
        if (!isContentFragment(fragment)) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_INVALID,
                    command.fragmentPath() + " is there and is not a content fragment; what is"
                            + " there is something else");
        }
        final Resource data = fragment.getChild(
                ListChildPagesHandler.PAGE_CONTENT + "/" + FragmentHandlers.DATA_NODE);
        if (data == null) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_INVALID,
                    command.fragmentPath() + " is a content fragment with no data node, which is a"
                            + " repository in a state the platform does not produce");
        }
        final Resource variation = data.getChild(command.variation());
        return variation == null
                ? new MutationOutcome.Refused(FragmentHandlers.VARIATION_NOT_FOUND,
                        command.variation() + " is not a variation of " + command.fragmentPath()
                                + ". It is refused rather than written to the master one, because"
                                + " writing somewhere other than where a caller said is how a"
                                + " translation ends up on the original.")
                : modelled(command, fragment, data, variation, session);
    }

    private static MutationOutcome modelled(UpdateContentFragmentCommand command, Resource fragment,
                                            Resource data, Resource variation,
                                            ResourceResolver session) {
        final String modelPath = data.getValueMap()
                .get(FragmentHandlers.MODEL_PROPERTY, String.class);
        if (modelPath == null) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_INVALID,
                    command.fragmentPath() + " names no model, so there is nothing to say which"
                            + " elements it has");
        }
        final FragmentModel.Outcome read = FragmentModel.at(session, modelPath);
        if (!(read instanceof final FragmentModel.Read held)) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_INVALID,
                    command.fragmentPath() + " names " + modelPath + " as its model, and that is"
                            + " not a model this caller can read");
        }
        final Optional<String> unknown = held.model().unknownIn(command.elements());
        return unknown.isPresent()
                ? new MutationOutcome.Refused(FragmentHandlers.ELEMENT_UNKNOWN, unknown.get()
                        + " is not an element " + modelPath + " declares")
                : written(command, fragment, variation, session);
    }

    private static MutationOutcome written(UpdateContentFragmentCommand command, Resource fragment,
                                           Resource variation, ResourceResolver session) {
        final ModifiableValueMap values = variation.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_ACCESS_DENIED,
                    command.fragmentPath() + " is not a fragment this caller may change");
        }
        command.elements().values().forEach((name, held) -> values.put(name, stored(held)));
        if (UpdateContentFragmentCommand.TITLE_UNCHANGED.equals(command.title())) {
            return sealed(session, FragmentResult.documentOf(command.fragmentPath()));
        }
        final Optional<ModifiableValueMap> titled =
                Optional.ofNullable(fragment.getChild(ListChildPagesHandler.PAGE_CONTENT))
                        .map(content -> content.adaptTo(ModifiableValueMap.class));
        if (titled.isEmpty()) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_ACCESS_DENIED,
                    command.fragmentPath() + " is not a fragment this caller may rename");
        }
        titled.orElseThrow().put(ListChildPagesHandler.TITLE_PROPERTY, command.title());
        return sealed(session, FragmentResult.documentOf(command.fragmentPath()));
    }

    private Answer experienceCreation(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final CreateExperienceFragmentCommand.Outcome asked =
                CreateExperienceFragmentCommand.of(arguments, contract);
        if (asked instanceof final CreateExperienceFragmentCommand.Refused refused) {
            return new Failed(FragmentHandlers.PARENT_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session ->
                madeExperience(((CreateExperienceFragmentCommand.Held) asked).command(), session));
    }

    private static MutationOutcome madeExperience(CreateExperienceFragmentCommand command,
                                                  ResourceResolver session) {
        final Resource parent = session.getResource(command.parentPath());
        if (parent == null) {
            return new MutationOutcome.Refused(FragmentHandlers.PARENT_NOT_FOUND,
                    command.parentPath() + " is not a path this caller can reach");
        }
        if (session.getResource(command.targetPath()) != null) {
            return new MutationOutcome.Refused(FragmentHandlers.TARGET_ALREADY_EXISTS,
                    command.targetPath() + " is already there, and this command replaces nothing");
        }
        final Resource template = session.getResource(command.templatePath());
        if (template == null) {
            return new MutationOutcome.Refused(FragmentHandlers.TEMPLATE_NOT_FOUND,
                    command.templatePath() + " is not there. A variation made without its template"
                            + " renders as nothing, so this is refused rather than made untyped.");
        }
        if (!CreatePageHandler.TEMPLATE_TYPE.equals(String.valueOf(template.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(FragmentHandlers.TEMPLATE_INVALID,
                    command.templatePath() + " is there and is not a template; what is there is"
                            + " something else");
        }
        return builtExperience(command, parent, session);
    }

    private static MutationOutcome builtExperience(CreateExperienceFragmentCommand command,
                                                   Resource parent, ResourceResolver session) {
        try {
            final Resource fragment = session.create(parent, command.name(), Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY,
                    FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
            session.create(fragment, ListChildPagesHandler.PAGE_CONTENT,
                    contentOf(command.title(), Map.of("sling:resourceType",
                            FragmentHandlers.EXPERIENCE_FRAGMENT_RESOURCE_TYPE)));
            final Resource variation = session.create(fragment, command.variationName(), Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY,
                    FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
            session.create(variation, ListChildPagesHandler.PAGE_CONTENT,
                    contentOf(command.title(), Map.of(CreatePageHandler.TEMPLATE_PROPERTY,
                            command.templatePath())));
            return sealed(session, CreateExperienceFragmentResult.documentOf(command.targetPath(),
                    command.variationPath()));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(FragmentHandlers.COMMIT_FAILED,
                    "the repository refused this fragment: " + refused.getMessage());
        }
    }

    /**
     * One content node's properties: what it is, what it is called, and what it carries.
     *
     * @param title what it is called to a person, or the empty title where the caller named none
     * @param carried what else belongs on it
     * @return the properties to create it with
     */
    private static Map<String, Object> contentOf(String title, Map<String, Object> carried) {
        final Map<String, Object> content = new LinkedHashMap<>();
        content.put(ListChildPagesHandler.TYPE_PROPERTY, "cq:PageContent");
        content.putAll(carried);
        if (!CreateExperienceFragmentCommand.NO_TITLE.equals(title)) {
            content.put(ListChildPagesHandler.TITLE_PROPERTY, title);
        }
        return content;
    }

    private Answer experienceUpdate(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final UpdateExperienceFragmentCommand.Outcome asked =
                UpdateExperienceFragmentCommand.of(arguments, contract);
        if (asked instanceof final UpdateExperienceFragmentCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session -> changedExperience(
                ((UpdateExperienceFragmentCommand.Held) asked).command(), session));
    }

    private static MutationOutcome changedExperience(UpdateExperienceFragmentCommand command,
                                                     ResourceResolver session) {
        final Resource variation = session.getResource(command.variationPath());
        if (variation == null) {
            return new MutationOutcome.Refused(FragmentHandlers.VARIATION_NOT_FOUND,
                    command.variationPath() + " is not a path this caller can reach");
        }
        if (!FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE.equals(String.valueOf(variation
                .getValueMap().get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(FragmentHandlers.VARIATION_INVALID,
                    command.variationPath() + " is there and is not a variation; what is there is"
                            + " something else");
        }
        final Resource content = variation.getChild(ListChildPagesHandler.PAGE_CONTENT);
        if (content == null) {
            return new MutationOutcome.Refused(FragmentHandlers.VARIATION_INVALID,
                    command.variationPath() + " is a variation with no content node, which is a"
                            + " repository in a state the platform does not produce");
        }
        return appliedTo(command, content, session);
    }

    private static MutationOutcome appliedTo(UpdateExperienceFragmentCommand command,
                                             Resource content, ResourceResolver session) {
        final ModifiableValueMap values = content.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            return new MutationOutcome.Refused(FragmentHandlers.VARIATION_ACCESS_DENIED,
                    command.variationPath() + " is not a variation this caller may change");
        }
        final Optional<String> immovable = command.change().immovableIn(values);
        if (immovable.isPresent()) {
            return new MutationOutcome.Refused(FragmentHandlers.PROPERTY_NOT_REMOVABLE,
                    immovable.get() + " is a property this repository will not let go of, and the"
                            + " whole change is refused rather than applied without it");
        }
        command.change().set().forEach((name, value) -> values.put(name, value.stored()));
        if (!UpdateExperienceFragmentCommand.TITLE_UNCHANGED.equals(command.title())) {
            values.put(ListChildPagesHandler.TITLE_PROPERTY, command.title());
        }
        return sealed(session, FragmentResult.documentOf(command.variationPath()));
    }

    private Answer removal(DocumentValue.Mapping arguments, ResourceResolver resolver,
                           CallerContext context) {
        final FragmentDeletion.Outcome asked = FragmentDeletion.of(arguments, contract);
        if (asked instanceof final FragmentDeletion.Refused refused) {
            return new Failed(FragmentHandlers.FRAGMENT_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        final Kind removing = kind;
        return committed(resolver, session -> removed(((FragmentDeletion.Held) asked).command(),
                session, contract.value(ContractLimit.MAXIMUM_DELETED_NODES),
                new Reach(context.discovery().limit(), removing)));
    }

    /**
     * How far a delete may look, and which kind of fragment it is looking at.
     *
     * @param budget how many resources one search may visit
     * @param kind which of the six is running
     */
    private record Reach(long budget, Kind kind) {
    }

    private static MutationOutcome removed(FragmentDeletion command, ResourceResolver session,
                                           long bound, Reach reach) {
        final Resource fragment = session.getResource(command.fragmentPath());
        if (fragment == null) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_NOT_FOUND,
                    command.fragmentPath() + " is not there. A caller told a delete succeeded"
                            + " believes something is gone that is not.");
        }
        if (!isKind(fragment, reach.kind())) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_INVALID,
                    command.fragmentPath() + " is there and is not a fragment of the kind this"
                            + " command removes; what is there is something else");
        }
        final List<String> subtree = RepositoryReach.under(fragment, bound);
        if (subtree.size() > bound) {
            return new MutationOutcome.Refused(FragmentHandlers.DELETION_BUDGET_EXCEEDED,
                    "this fragment holds more than the " + bound + " nodes one delete may remove");
        }
        if (command.referencePolicy() == ReferencePolicy.REFUSE_WHEN_REFERENCED
                && !RepositoryReach.pointingAt(session, command.fragmentPath(), reach.budget())
                        .isEmpty()) {
            return new MutationOutcome.Refused(FragmentHandlers.FRAGMENT_IS_REFERENCED,
                    command.fragmentPath() + " is used somewhere, and this request asked to be"
                            + " refused when it is");
        }
        try {
            session.delete(fragment);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(FragmentHandlers.COMMIT_FAILED,
                    "the repository refused this delete: " + refused.getMessage());
        }
        return sealed(session,
                DeletedResourceResult.documentOf(command.fragmentPath(), subtree.size()));
    }

    private static boolean isKind(Resource fragment, Kind kind) {
        return kind == Kind.CONTENT_REMOVAL
                ? isContentFragment(fragment)
                : FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE.equals(String.valueOf(fragment
                        .getValueMap().get(ListChildPagesHandler.TYPE_PROPERTY, String.class)));
    }

    /**
     * Whether one node is a content fragment rather than any other asset.
     *
     * <p>The flag rather than the type, because the type is the one every asset has. An image and a
     * fragment are both {@code dam:Asset}, and a delete that told them apart by type alone would
     * remove a photograph when it was asked to remove a fragment.</p>
     *
     * @param fragment the node to look at
     * @return whether it is one
     */
    private static boolean isContentFragment(Resource fragment) {
        final Resource content = fragment.getChild(ListChildPagesHandler.PAGE_CONTENT);
        return FragmentHandlers.CONTENT_FRAGMENT_TYPE.equals(String.valueOf(fragment.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))
                && content != null
                && content.getValueMap().get(FragmentHandlers.CONTENT_FRAGMENT_FLAG, false);
    }

    private static MutationOutcome sealed(ResourceResolver session, DocumentValue.Mapping result) {
        try {
            session.commit();
            return new MutationOutcome.Changed(result);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(FragmentHandlers.COMMIT_FAILED,
                    "the repository refused this change: " + refused.getMessage());
        }
    }

    private static Answer committed(ResourceResolver resolver, SingleCommit.Mutation mutation) {
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                mutation), FragmentHandlers.COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case CONTENT_CREATION -> FragmentHandlers.contentCreationCategories();
            case CONTENT_UPDATE -> FragmentHandlers.contentUpdateCategories();
            case EXPERIENCE_CREATION -> FragmentHandlers.experienceCreationCategories();
            case EXPERIENCE_UPDATE -> FragmentHandlers.experienceUpdateCategories();
            case CONTENT_REMOVAL, EXPERIENCE_REMOVAL -> FragmentHandlers.removalCategories();
        };
    }
}
