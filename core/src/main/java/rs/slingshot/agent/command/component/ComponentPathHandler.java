// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.ComponentPlacement;
import rs.slingshot.agent.command.mutation.DeletedResourceResult;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Changes, removes or reorders one component, in one commit.
 *
 * <p>One handler for three commands because the three share everything but their last step: each
 * finds the component, each is held to one commit, and each answers with the address it acted on.
 * Which of the three it is comes from the shape it was constructed for rather than from an
 * argument, so the three keep separate registry rows, separate failure sets and separate bounds.
 * </p>
 */
public final class ComponentPathHandler implements CommandHandler {

    /** The category a component nothing is at is refused under. */
    public static final String COMPONENT_NOT_FOUND = "component_not_found";

    /** The category a component the caller may not change is refused under. */
    public static final String COMPONENT_ACCESS_DENIED = "component_access_denied";

    /** The category something that is there and is not a component is refused under. */
    public static final String COMPONENT_INVALID = "component_invalid";

    /** The category a property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a property the repository will not let go of is refused under. */
    public static final String PROPERTY_NOT_REMOVABLE = "property_not_removable";

    /** The category a parent that cannot hold an order is refused under. */
    public static final String PARENT_NOT_ORDERABLE = "parent_not_orderable";

    /** The category a neighbour that is not among the siblings is refused under. */
    public static final String SIBLING_NOT_FOUND = "sibling_not_found";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    private final AgentContract contract;
    private final ComponentPathCommand.Shape shape;

    /**
     * Holds one handler for one of the three shapes.
     *
     * @param contract the authenticated contract
     * @param shape which of the three commands this handler answers
     */
    public ComponentPathHandler(AgentContract contract, ComponentPathCommand.Shape shape) {
        this.contract = contract;
        this.shape = shape;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        // Matched over the whole sealed set so every shape of answer is dealt with here, which is
        // what lets the steps below take a command rather than something that might be a refusal.
        // Read through each command's own entry point, so the model a schema names is the model
        // that actually reads that command's argument rather than one it merely resembles.
        return switch (asked(arguments)) {
            case ComponentPathCommand.Refused refused ->
                    new Failed(categoryFor(shape, refused.refusal()),
                            refused.refusal() + ": " + refused.detail());
            case ComponentPathCommand.Held held -> MutationAnswer.of(
                    SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                            session -> acted(held.command(), session)),
                    COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
            case ComponentPathCommand.Placed placed -> MutationAnswer.of(
                    SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                            session -> reordered(placed.command(), placed.placement(), session)),
                    COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
        };
    }

    private ComponentPathCommand.Outcome asked(DocumentValue.Mapping arguments) {
        return switch (shape) {
            case UPDATE -> UpdateComponentCommand.of(arguments, contract);
            case DELETE -> DeleteComponentCommand.of(arguments, contract);
            case REORDER -> ReorderComponentCommand.of(arguments, contract);
        };
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * <p>Which shape it is matters: the three declare different failure sets, and a refusal mapped
     * onto a category its own row does not declare is a category a caller is told about and cannot
     * interpret. A change rejection means nothing to a removal, and a placement rejection means
     * nothing to a change.</p>
     *
     * @param shape which of the three commands
     * @param refusal why the argument was refused
     * @return the category that command's row declares for it
     */
    public static String categoryFor(ComponentPathCommand.Shape shape,
                                     ComponentPathCommand.Refusal refusal) {
        return switch (refusal) {
            case CHANGE_REJECTED -> shape == ComponentPathCommand.Shape.UPDATE
                    ? PROPERTY_REJECTED : COMPONENT_NOT_FOUND;
            case PLACEMENT_REJECTED -> shape == ComponentPathCommand.Shape.REORDER
                    ? SIBLING_NOT_FOUND : COMPONENT_NOT_FOUND;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH ->
                    COMPONENT_NOT_FOUND;
        };
    }

    private MutationOutcome acted(ComponentPathCommand command, ResourceResolver session) {
        final Resource component = session.getResource(command.componentPath());
        if (component == null) {
            return absent(command);
        }
        return shape == ComponentPathCommand.Shape.UPDATE
                ? changed(command, component, session) : removed(command, component, session);
    }

    private static MutationOutcome reordered(ComponentPathCommand command,
                                             ComponentPlacement placement,
                                             ResourceResolver session) {
        final Resource component = session.getResource(command.componentPath());
        return component == null ? absent(command)
                : placed(command, placement, component, session);
    }

    private static MutationOutcome absent(ComponentPathCommand command) {
        return new MutationOutcome.Refused(COMPONENT_NOT_FOUND, command.componentPath()
                + " is not a path this caller can reach, which is the same answer as nothing being"
                + " there");
    }

    private static MutationOutcome changed(ComponentPathCommand command, Resource component,
                                           ResourceResolver session) {
        final ModifiableValueMap values = component.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            return new MutationOutcome.Refused(COMPONENT_ACCESS_DENIED, command.componentPath()
                    + " is not a component this caller may change");
        }
        final Optional<String> immovable = command.change().immovableIn(values);
        if (immovable.isPresent()) {
            return new MutationOutcome.Refused(PROPERTY_NOT_REMOVABLE, immovable.get() + " is a"
                    + " property this repository will not let go of, and the whole change is"
                    + " refused rather than applied without it");
        }
        command.change().set().forEach((name, value) -> values.put(name, value.stored()));
        return committed(session, UpdateComponentResult.documentOf(command.componentPath()));
    }

    private static MutationOutcome removed(ComponentPathCommand command, Resource component,
                                           ResourceResolver session) {
        final long count = under(component);
        try {
            session.delete(component);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this removal: " + refused.getMessage());
        }
        return committed(session,
                DeletedResourceResult.documentOf(command.componentPath(), count));
    }

    private static MutationOutcome placed(ComponentPathCommand command,
                                          ComponentPlacement placement, Resource component,
                                          ResourceResolver session) {
        final Resource parent = component.getParent();
        if (parent == null) {
            return new MutationOutcome.Refused(PARENT_NOT_ORDERABLE, command.componentPath()
                    + " has no parent to be ordered within");
        }
        if (!AddComponentHandler.ORDERED_TYPE.equals(String.valueOf(parent.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(PARENT_NOT_ORDERABLE, parent.getPath() + " keeps"
                    + " its children in no particular order, so there is no order to move this"
                    + " component within");
        }
        final Optional<String> sibling = ComponentPlacement.siblingOf(placement);
        if (sibling.isPresent() && parent.getChild(sibling.orElseThrow()) == null) {
            return new MutationOutcome.Refused(SIBLING_NOT_FOUND, sibling.orElseThrow() + " is not"
                    + " among this component's siblings. A neighbour that is not there is a"
                    + " refusal a caller can act on, where a position by index would have put the"
                    + " component somewhere nobody asked for.");
        }
        return moved(command, parent, placement, session);
    }

    /**
     * Puts one component where the caller asked, through the repository's own ordering.
     *
     * <p>Ordered through the node rather than through the resolver. This is a command that writes,
     * so it is given the caller's own session and may use it — the wrapper that forbids reaching a
     * session is the one read commands are given, and it is what makes that a guarantee rather than
     * a habit.</p>
     *
     * @param command what was asked
     * @param parent the component's parent
     * @param sibling the neighbour to go before, or nothing to go last
     * @param session the caller's own session, counting commits
     * @return what happened
     */
    private static MutationOutcome moved(ComponentPathCommand command, Resource parent,
                                         ComponentPlacement placement, ResourceResolver session) {
        final String name = command.componentPath()
                .substring(command.componentPath().lastIndexOf('/') + 1);
        final Node ordering = parent.adaptTo(Node.class);
        if (ordering == null) {
            return new MutationOutcome.Refused(PARENT_NOT_ORDERABLE, parent.getPath() + " is not a"
                    + " node this repository can order the children of");
        }
        try {
            ordered(ordering, parent, name, placement);
        } catch (final RepositoryException refused) {
            return new MutationOutcome.Refused(PARENT_NOT_ORDERABLE, parent.getPath() + " would"
                    + " not take an order: " + refused.getMessage());
        }
        return committed(session, ReorderComponentResult.documentOf(command.componentPath(),
                precedingIn(parent, name)));
    }

    /**
     * Puts one component where the placement says, naming a neighbour every time.
     *
     * <p>The repository spells "put it at the end" as an absent neighbour, and this build does not
     * pass one. So the end is asked for the way it is meant: everything that currently follows this
     * component is moved in front of it, one named neighbour at a time, and what is left is the
     * component last. Bounded by the siblings of one parent, which is what a page holds.</p>
     *
     * @param ordering the parent as a node that can be ordered
     * @param parent the parent as a resource, for reading its children
     * @param name the component's own name
     * @param placement where it goes
     * @throws RepositoryException if the repository will not take the order
     */
    private static void ordered(Node ordering, Resource parent, String name,
                                ComponentPlacement placement) throws RepositoryException {
        final Optional<String> sibling = ComponentPlacement.siblingOf(placement);
        if (sibling.isPresent()) {
            ordering.orderBefore(name, sibling.orElseThrow());
            return;
        }
        for (final String following : after(parent, name)) {
            ordering.orderBefore(following, name);
        }
    }

    /**
     * The siblings that currently follow one component.
     *
     * @param parent the parent
     * @param name the component's own name
     * @return their names, in the order they are in
     */
    private static List<String> after(Resource parent, String name) {
        final List<String> following = new java.util.ArrayList<>();
        boolean reached = false;
        final Iterator<Resource> children = parent.listChildren();
        while (children.hasNext()) {
            final String child = children.next().getName();
            if (reached) {
                following.add(child);
            }
            reached = reached || child.equals(name);
        }
        return List.copyOf(following);
    }

    /**
     * What one component now sits behind among its siblings.
     *
     * <p>Read back from the parent after the move rather than worked out from what was asked for.
     * The caller named what to go before; what they need to check is what ended up in front, and a
     * re-derivation would agree with the repository right up until it did not.</p>
     *
     * @param parent the component's parent
     * @param name the component's own name
     * @return the neighbour in front of it, or nothing where it is first
     */
    private static String precedingIn(Resource parent, String name) {
        String preceding = ReorderComponentResult.NOTHING_IN_FRONT;
        final Iterator<Resource> children = parent.listChildren();
        while (children.hasNext()) {
            final Resource child = children.next();
            if (child.getName().equals(name)) {
                return preceding;
            }
            preceding = child.getName();
        }
        return ReorderComponentResult.NOTHING_IN_FRONT;
    }

    private static MutationOutcome committed(ResourceResolver session,
                                             DocumentValue.Mapping result) {
        try {
            session.commit();
            return new MutationOutcome.Changed(result);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(COMMIT_FAILED,
                    "the repository refused this change: " + refused.getMessage());
        }
    }

    private static long under(Resource component) {
        long counted = 0;
        final Deque<Resource> pending = new ArrayDeque<>(List.of(component));
        while (!pending.isEmpty()) {
            final Resource held = pending.removeFirst();
            counted = counted + 1;
            final Iterator<Resource> children = held.listChildren();
            while (children.hasNext()) {
                pending.addLast(children.next());
            }
        }
        return counted;
    }

    @Override
    public List<String> categories() {
        return declaredCategories(shape);
    }

    /**
     * Everything one of the three can fail with, which its own registry row declares exactly.
     *
     * @param shape which of the three
     * @return the categories
     */
    public static List<String> declaredCategories(ComponentPathCommand.Shape shape) {
        return switch (shape) {
            case UPDATE -> List.of(COMPONENT_NOT_FOUND, COMPONENT_ACCESS_DENIED, COMPONENT_INVALID,
                    PROPERTY_REJECTED, PROPERTY_NOT_REMOVABLE, COMMIT_FAILED,
                    SingleCommit.OUTCOME_UNKNOWN);
            case DELETE -> List.of(COMPONENT_NOT_FOUND, COMPONENT_ACCESS_DENIED, COMPONENT_INVALID,
                    COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
            case REORDER -> List.of(COMPONENT_NOT_FOUND, COMPONENT_ACCESS_DENIED,
                    PARENT_NOT_ORDERABLE, SIBLING_NOT_FOUND, COMMIT_FAILED,
                    SingleCommit.OUTCOME_UNKNOWN);
        };
    }
}
