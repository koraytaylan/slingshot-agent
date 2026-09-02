// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which content fragment to change, which variation of it, and what to set.
 *
 * <p>The variation may be left out, and where it is the master one is meant. That is the one place
 * this command guesses, and it guesses the only thing it can: every content fragment has a master
 * variation and none of them is optional, so a caller who named nothing named the one that is
 * always there. A caller who named a variation that is not there is refused rather than written to
 * master, because writing somewhere other than where they said is how a translation ends up on the
 * original.</p>
 *
 * <p>An element the fragment's model has never heard of is refused rather than written as a loose
 * property. The refusal names the element; it does not name its value, because the value is the
 * caller's own content and a log is a place other people read.</p>
 *
 * @param fragmentPath the fragment to change
 * @param variationName which variation to write to, or {@link #VARIATION_UNNAMED} for the
 *     master one
 * @param title what the fragment is called to a person, or {@link #TITLE_UNCHANGED}
 * @param elements what to set, which may name none and then change none
 */
public record UpdateContentFragmentCommand(String fragmentPath, String variationName,
                                           String title, FragmentElements elements) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "update_content_fragment";

    /** The member the fragment's address is carried in. */
    public static final String FRAGMENT_PATH = "fragment_path";

    /** The member the variation's name is carried in. */
    public static final String VARIATION_NAME = "variation_name";

    /** The member the fragment's title is carried in. */
    public static final String TITLE = "title";

    /** What the variation says when the caller named none, which means the master one. */
    public static final String VARIATION_UNNAMED = "";

    /** What the title says when the caller named none, so the fragment keeps the one it has. */
    public static final String TITLE_UNCHANGED = FragmentPaths.NO_TITLE;

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS = List.of(FragmentElements.ARGUMENT_MEMBER,
            FRAGMENT_PATH, TITLE, VARIATION_NAME);

    /** The member a caller has to send; every change this command makes is optional. */
    public static final List<String> REQUIRED = List.of(FRAGMENT_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The fragment is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The variation's name is empty, too long, or made of something a name is not. */
        VARIATION_NAME_REJECTED,
        /** The title is longer than the contract allows. */
        TITLE_TOO_LONG,
        /** The elements are not ones this contract writes. */
        ELEMENTS_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(UpdateContentFragmentCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the element rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Which variation this writes to, which is the master one where the caller named none.
     *
     * @return the variation's node name
     */
    public String variation() {
        return VARIATION_UNNAMED.equals(variationName)
                ? FragmentHandlers.MASTER_VARIATION : variationName;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address, the name and the values
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming a fragment and what to change about it");
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
            return new Refused(Refusal.MEMBER_ABSENT,
                    absent.get() + " is required; this command does not choose which fragment");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> fragment =
                FragmentPaths.absolute(mapping, FRAGMENT_PATH, contract);
        if (fragment.isEmpty()) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, FRAGMENT_PATH + " is an absolute path"
                    + " beginning at the root, within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)
                    + " a path may be");
        }
        final String variation =
                variationOf(mapping, contract).orElse(VARIATION_UNNAMED);
        if (mapping.member(VARIATION_NAME).isPresent()
                && VARIATION_UNNAMED.equals(variation)) {
            return new Refused(Refusal.VARIATION_NAME_REJECTED, VARIATION_NAME + " is one"
                    + " variation's own name: not empty, not a path, and within the "
                    + contract.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_VARIATION_NAME_BYTES)
                    + " a variation's name may be");
        }
        final FragmentPaths.TitleOutcome title = FragmentPaths.title(mapping, TITLE, contract);
        return title instanceof final FragmentPaths.TitleRefused refused
                ? new Refused(Refusal.TITLE_TOO_LONG, refused.detail())
                : elemented(fragment.orElseThrow(), variation,
                        ((FragmentPaths.TitleHeld) title).title(), mapping, contract);
    }

    private static Optional<String> variationOf(DocumentValue.Mapping mapping,
                                                AgentContract contract) {
        final Optional<DocumentValue> held = mapping.member(VARIATION_NAME);
        if (held.isEmpty() || !(held.orElseThrow() instanceof final DocumentValue.Text text)
                || text.value().isBlank() || text.value().indexOf('/') >= 0
                || text.value().length() > contract.value(
                        ContractLimit.MAXIMUM_CONTENT_FRAGMENT_VARIATION_NAME_BYTES)) {
            return Optional.empty();
        }
        return Optional.of(text.value());
    }

    private static Outcome elemented(String fragment, String variation, String title,
                                     DocumentValue.Mapping mapping, AgentContract contract) {
        final FragmentElements.Outcome elements = FragmentElements.of(mapping, contract);
        return elements instanceof final FragmentElements.Refused refused
                ? new Refused(Refusal.ELEMENTS_REJECTED,
                        refused.refusal() + ": " + refused.detail())
                : new Held(new UpdateContentFragmentCommand(fragment, variation, title,
                        ((FragmentElements.Held) elements).elements()));
    }
}
