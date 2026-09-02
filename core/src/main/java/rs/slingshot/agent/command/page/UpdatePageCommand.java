// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which page to change, and what to change about it.
 *
 * <p>The command where the absent-property question is decided, and it is decided by having two
 * lists. A property in neither is left exactly as it was: an update that read absence as a removal
 * would make a caller who sent a partial view destroy the rest of the page, and one that read an
 * empty value as a removal would make an intentionally empty title impossible to write.</p>
 *
 * <p>The title is its own member rather than a property among the others, because that is how the
 * client sends it — and it is bounded as a page title rather than as a property string, which is
 * the client's own distinction between what a page is called and what it happens to hold.</p>
 *
 * @param pagePath the page to change
 * @param title what to call it, or empty where the caller is not changing that
 * @param change what to write and what to take away
 */
public record UpdatePageCommand(String pagePath, String title, PropertyChange change) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "update_page";

    /** The member the page's address is carried in. */
    public static final String PAGE_PATH = "page_path";

    /** The member the page's new title is carried in, where the caller is changing it. */
    public static final String TITLE = "title";

    /** Where a caller is not changing the title. */
    public static final String TITLE_UNCHANGED = "";

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS = List.of(PAGE_PATH, PropertyChange.PROPERTIES,
            PropertyValue.CARDINALITY, PropertyChange.REMOVED_PROPERTY_NAMES, TITLE);

    /** The member a caller has to send; every change this command makes is optional. */
    public static final List<String> REQUIRED = List.of(PAGE_PATH);

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
        /** The title is longer than the contract allows. */
        TITLE_TOO_LONG,
        /** The change is not one this contract makes. */
        CHANGE_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(UpdatePageCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the property rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether this update would leave the page exactly as it was.
     *
     * @return whether it would, which a handler answers rather than committing nothing
     */
    public boolean isEmpty() {
        return change.isEmpty() && TITLE_UNCHANGED.equals(title);
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address, the title and the lists
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming a page and what to change about it");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(PAGE_PATH).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    PAGE_PATH + " is required; this command chooses no page for a caller");
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
        final Optional<DocumentValue> asked = mapping.member(TITLE);
        final long bound = contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES);
        if (asked.isPresent() && (!(asked.orElseThrow() instanceof final DocumentValue.Text title)
                || title.value().length() > bound)) {
            return new Refused(Refusal.TITLE_TOO_LONG,
                    TITLE + " is text within the " + bound + " a page's title may be");
        }
        return changed(page.value(), asked
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(TITLE_UNCHANGED), mapping, contract);
    }

    private static Outcome changed(String page, String title, DocumentValue.Mapping mapping,
                                   AgentContract contract) {
        final PropertyChange.Outcome change = PropertyChange.of(mapping, contract);
        return change instanceof final PropertyChange.Refused refused
                ? new Refused(Refusal.CHANGE_REJECTED, refused.refusal() + ": " + refused.detail())
                : new Held(new UpdatePageCommand(page, title,
                        ((PropertyChange.Held) change).change()));
    }
}
