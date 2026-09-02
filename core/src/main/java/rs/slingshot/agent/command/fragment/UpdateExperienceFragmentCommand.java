// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which experience fragment variation to change, and what to change about it.
 *
 * <p>The variation is addressed, not the fragment. An experience fragment is a container and each
 * variation is what actually renders, so a caller changing the container would change nothing
 * anybody sees. Addressing the variation is what makes the command mean something.</p>
 *
 * <p>The same two lists a page update carries, and for the same reason: a property named in
 * neither is left as it was, so a caller who sent a partial view does not silently drop what the
 * last edit put there.</p>
 *
 * @param variationPath the variation to change
 * @param title what the variation is called to a person, or {@link #TITLE_UNCHANGED}
 * @param change what to write and what to take away
 */
public record UpdateExperienceFragmentCommand(String variationPath, String title,
                                              PropertyChange change) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "update_experience_fragment";

    /** The member the variation's address is carried in. */
    public static final String VARIATION_PATH = "variation_path";

    /** The member the variation's title is carried in. */
    public static final String TITLE = "title";

    /** What the title says when the caller named none, so the variation keeps the one it has. */
    public static final String TITLE_UNCHANGED = FragmentPaths.NO_TITLE;

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS = List.of(PropertyChange.PROPERTIES,
            PropertyValue.CARDINALITY, PropertyChange.REMOVED_PROPERTY_NAMES, TITLE,
            VARIATION_PATH);

    /** The member a caller has to send; every change this command makes is optional. */
    public static final List<String> REQUIRED = List.of(VARIATION_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The variation is not an absolute repository path. */
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
    public record Held(UpdateExperienceFragmentCommand command) implements Outcome {
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
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address and both lists
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming a variation and what to change about it");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; a fragment is a"
                    + " container and a variation is what renders, so this command does not choose"
                    + " which one");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> variation =
                FragmentPaths.absolute(mapping, VARIATION_PATH, contract);
        if (variation.isEmpty()) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, VARIATION_PATH + " is an absolute path"
                    + " beginning at the root, within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)
                    + " a path may be");
        }
        final FragmentPaths.TitleOutcome title = FragmentPaths.title(mapping, TITLE, contract);
        if (title instanceof final FragmentPaths.TitleRefused refused) {
            return new Refused(Refusal.TITLE_TOO_LONG, refused.detail());
        }
        final PropertyChange.Outcome change = PropertyChange.of(mapping, contract);
        return change instanceof final PropertyChange.Refused refused
                ? new Refused(Refusal.CHANGE_REJECTED, refused.refusal() + ": " + refused.detail())
                : new Held(new UpdateExperienceFragmentCommand(variation.orElseThrow(),
                        ((FragmentPaths.TitleHeld) title).title(),
                        ((PropertyChange.Held) change).change()));
    }
}
