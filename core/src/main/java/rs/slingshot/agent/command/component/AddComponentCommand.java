// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A component to add: which page, where inside it, what it is called and what it is.
 *
 * <p>It goes last among its siblings. Where exactly it goes afterwards is the reorder command's
 * question, and keeping the two apart means adding one never quietly moves the others — a caller
 * who wanted it third asks for that, and can see whether it worked.</p>
 *
 * @param pagePath the page it goes in
 * @param contentParent where inside that page
 * @param componentName what the component's own node is called
 * @param resourceType what the component is
 * @param properties what to write on it at birth
 */
public record AddComponentCommand(String pagePath, ComponentParent contentParent,
                                  String componentName, String resourceType,
                                  PropertyChange properties) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "add_component";

    /** The member the page's address is carried in. */
    public static final String PAGE_PATH = "page_path";

    /** The member the component's own node name is carried in. */
    public static final String COMPONENT_NAME = "component_name";

    /** The member the component's resource type is carried in. */
    public static final String RESOURCE_TYPE = "resource_type";

    /**
     * The member the initial properties are carried in.
     *
     * <p>Its own constant rather than the change vocabulary's, because this command takes the
     * writing half and not the removal half: there is nothing to remove from a component that does
     * not exist yet, and borrowing the whole vocabulary would declare a member the client's own
     * schema does not have.</p>
     */
    public static final String PROPERTIES = "properties";

    /** Every member this command's argument has, and there is no seventh. */
    public static final List<String> MEMBERS = List.of(COMPONENT_NAME,
            ComponentParent.ARGUMENT_MEMBER, PAGE_PATH, PROPERTIES, PropertyValue.CARDINALITY,
            RESOURCE_TYPE);

    /** The members a caller has to send; only the initial properties may be left out. */
    public static final List<String> REQUIRED =
            List.of(COMPONENT_NAME, ComponentParent.ARGUMENT_MEMBER, PAGE_PATH, RESOURCE_TYPE);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The page is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The parent is neither the content root nor a path relative to it. */
        PARENT_REJECTED,
        /** The component's own name is empty, too long, or carries a path. */
        NAME_REJECTED,
        /** The resource type is empty or longer than the contract allows. */
        RESOURCE_TYPE_REJECTED,
        /** The initial properties are not ones this contract writes. */
        PROPERTIES_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(AddComponentCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every address, name and type
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which page a component goes in and what it"
                            + " is");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final Optional<String> absent = REQUIRED.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; this command"
                    + " chooses neither where a component goes nor what it is");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(PAGE_PATH).orElseThrow() instanceof final DocumentValue.Text page)
                || page.value().isEmpty() || page.value().charAt(0) != '/'
                || page.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    PAGE_PATH + " is an absolute path beginning at the root");
        }
        final Optional<ComponentParent> parent =
                ComponentParent.of(mapping.member(ComponentParent.ARGUMENT_MEMBER).orElseThrow());
        if (parent.isEmpty()) {
            return new Refused(Refusal.PARENT_REJECTED, ComponentParent.ARGUMENT_MEMBER + " is "
                    + ComponentParent.CONTENT_ROOT + " or a path relative to it. A parent that"
                    + " could point anywhere would make the page this command was given"
                    + " decorative.");
        }
        if (!(mapping.member(COMPONENT_NAME).orElseThrow() instanceof final DocumentValue.Text name)
                || name.value().isBlank() || name.value().indexOf('/') >= 0
                || name.value().length() > contract.value(
                        ContractLimit.MAXIMUM_COMPONENT_NAME_BYTES)) {
            return new Refused(Refusal.NAME_REJECTED, COMPONENT_NAME + " is one node's own name:"
                    + " not empty, not a path, and within the "
                    + contract.value(ContractLimit.MAXIMUM_COMPONENT_NAME_BYTES)
                    + " a component's name may be");
        }
        if (!(mapping.member(RESOURCE_TYPE).orElseThrow() instanceof final DocumentValue.Text type)
                || type.value().isBlank() || type.value().length() > contract.value(
                        ContractLimit.MAXIMUM_COMPONENT_RESOURCE_TYPE_BYTES)) {
            return new Refused(Refusal.RESOURCE_TYPE_REJECTED, RESOURCE_TYPE + " says what the"
                    + " component is, and a component with no type renders as nothing");
        }
        return propertied(page.value(), parent.orElseThrow(), name.value(), type.value(), mapping,
                contract);
    }

    private static Outcome propertied(String page, ComponentParent parent, String name,
                                      String type, DocumentValue.Mapping mapping,
                                      AgentContract contract) {
        final PropertyChange.Outcome properties = PropertyChange.of(mapping, contract);
        if (properties instanceof final PropertyChange.Refused refused) {
            return new Refused(Refusal.PROPERTIES_REJECTED,
                    refused.refusal() + ": " + refused.detail());
        }
        return new Held(new AddComponentCommand(page, parent, name, type,
                ((PropertyChange.Held) properties).change()));
    }
}
