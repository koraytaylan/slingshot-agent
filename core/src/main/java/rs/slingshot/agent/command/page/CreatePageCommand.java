// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A page to make: where it goes, what it is called, and what it is made from.
 *
 * <p>The template is required and never inferred. A page created without one is a node that renders
 * as nothing — it exists, it is addressable, and every tool that opens it shows an empty screen —
 * which is worse than a refusal, because a refusal is noticed the same minute and an empty page is
 * noticed by whoever opens it next week.</p>
 *
 * <p>The initial properties are a {@link PropertyChange} whose removal list must be empty. There is
 * nothing to remove from a page that does not exist yet, and a caller who named a removal meant
 * something this command cannot do.</p>
 *
 * @param parentPath where the page goes
 * @param pageName what the page's own node is called, which is what appears in its address
 * @param title what the page is called to a person, which may be empty
 * @param templatePath what it is made from
 * @param initialProperties what to write on it beyond its title
 */
public record CreatePageCommand(String parentPath, String pageName, String title,
                                String templatePath, PropertyChange initialProperties) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "create_page";

    /** The member the parent's address is carried in. */
    public static final String PARENT_PATH = "parent_path";

    /** The member the page's own node name is carried in. */
    public static final String PAGE_NAME = "page_name";

    /** The member the page's title is carried in. */
    public static final String TITLE = "title";

    /** The member the template's address is carried in. */
    public static final String TEMPLATE_PATH = "template_path";

    /** The member the initial properties are carried in. */
    public static final String INITIAL_PROPERTIES = "initial_properties";

    /** Every member this command's argument has, and there is no sixth. */
    public static final List<String> MEMBERS = List.of(INITIAL_PROPERTIES, PAGE_NAME, PARENT_PATH,
            PropertyValue.CARDINALITY, TEMPLATE_PATH, TITLE);

    /** The members a caller has to send; only the initial properties may be left out. */
    public static final List<String> REQUIRED =
            List.of(PAGE_NAME, PARENT_PATH, TEMPLATE_PATH, TITLE);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** An address is not an absolute repository path, or is longer than the contract allows. */
        NOT_AN_ABSOLUTE_PATH,
        /** The page's own name is empty, too long, or made of something a node name is not. */
        NAME_REJECTED,
        /** The title is longer than the contract allows. */
        TITLE_TOO_LONG,
        /** The initial properties are not ones this contract writes. */
        PROPERTIES_REJECTED,
        /** A removal was named, and there is nothing to remove from a page that is not there. */
        REMOVAL_ON_CREATION
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(CreatePageCommand command) implements Outcome {
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
     * Where this page will be, which is the parent and the name joined.
     *
     * @return the address the page will have, which is never absent because both halves of it are
     *     required
     */
    public String targetPath() {
        return parentPath + "/" + pageName;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every address, name and title
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying where a page goes and what it is made from");
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
                    + " chooses neither where a page goes, what it is called, nor what it is made"
                    + " from");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> parent = absolute(mapping, PARENT_PATH, contract);
        final Optional<String> template = absolute(mapping, TEMPLATE_PATH, contract);
        if (parent.isEmpty() || template.isEmpty()) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, PARENT_PATH + " and " + TEMPLATE_PATH
                    + " are absolute paths beginning at the root, within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)
                    + " a path may be");
        }
        final Optional<Named> named = named(mapping, contract);
        return named.isEmpty()
                ? refusalOf(mapping, contract)
                : propertied(parent.orElseThrow(), template.orElseThrow(), named.orElseThrow(),
                        mapping, contract);
    }

    private static Optional<String> absolute(DocumentValue.Mapping mapping, String member,
                                             AgentContract contract) {
        if (!(mapping.member(member).orElseThrow() instanceof final DocumentValue.Text held)
                || held.value().isEmpty() || held.value().charAt(0) != '/'
                || held.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return Optional.empty();
        }
        return Optional.of(held.value());
    }

    /**
     * What a page is called, in both senses.
     *
     * @param pageName the node's own name
     * @param title what a person calls it
     */
    private record Named(String pageName, String title) {
    }

    private static Optional<Named> named(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(PAGE_NAME).orElseThrow() instanceof final DocumentValue.Text name)
                || name.value().isBlank() || name.value().indexOf('/') >= 0
                || name.value().length() > contract.value(ContractLimit.MAXIMUM_PAGE_NAME_BYTES)) {
            return Optional.empty();
        }
        if (!(mapping.member(TITLE).orElseThrow() instanceof final DocumentValue.Text title)
                || title.value().length() > contract.value(
                        ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES)) {
            return Optional.empty();
        }
        return Optional.of(new Named(name.value(), title.value()));
    }

    /**
     * Which of the two naming rules one argument broke, said as the caller would fix it.
     *
     * <p>Worked out only once something is known to be wrong, so the ordinary path reads both
     * values once rather than testing each against its own refusal on the way past.</p>
     *
     * @param mapping the argument document
     * @param contract the authenticated contract
     * @return the refusal
     */
    private static Outcome refusalOf(DocumentValue.Mapping mapping, AgentContract contract) {
        final long titleBound = contract.value(ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES);
        if (mapping.member(TITLE).orElseThrow() instanceof final DocumentValue.Text title
                && title.value().length() > titleBound) {
            return new Refused(Refusal.TITLE_TOO_LONG,
                    TITLE + " is longer than the " + titleBound + " a title may be");
        }
        return new Refused(Refusal.NAME_REJECTED, PAGE_NAME + " is one node's own name: not empty,"
                + " not a path, and within the "
                + contract.value(ContractLimit.MAXIMUM_PAGE_NAME_BYTES) + " a page's name may be");
    }

    private static Outcome propertied(String parent, String template, Named named,
                                      DocumentValue.Mapping mapping, AgentContract contract) {
        final PropertyChange.Outcome change =
                PropertyChange.of(initial(mapping), contract);
        if (change instanceof final PropertyChange.Refused refused) {
            return new Refused(Refusal.PROPERTIES_REJECTED,
                    refused.refusal() + ": " + refused.detail());
        }
        final PropertyChange held = ((PropertyChange.Held) change).change();
        if (!held.removed().isEmpty()) {
            return new Refused(Refusal.REMOVAL_ON_CREATION, "a removal was named, and there is"
                    + " nothing to remove from a page that does not exist yet");
        }
        return new Held(new CreatePageCommand(parent, named.pageName(), named.title(), template,
                held));
    }

    /**
     * The initial properties as a change document, which is what reads them.
     *
     * <p>Wrapped rather than read separately: what a caller may write on a new page is exactly what
     * they may write on an existing one, and one reader for both is what keeps those the same.</p>
     *
     * @param mapping the argument document
     * @return a document the change reader understands
     */
    private static DocumentValue.Mapping initial(DocumentValue.Mapping mapping) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        mapping.member(INITIAL_PROPERTIES)
                .ifPresent(properties -> written.put(PropertyChange.PROPERTIES, properties));
        return new DocumentValue.Mapping(written);
    }
}
