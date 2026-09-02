// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One content fragment, in one named variation.
 *
 * <p>The variation is required and has no default. A fragment is a model, its elements, and its
 * variations; reading one without saying which variation reads whichever somebody happened to make
 * first, and there is no sense in which that one is the right answer. A caller who has to name the
 * variation has chosen it; a caller who inherits one has not, and would not know they had.</p>
 *
 * <p>There is no result window either. This command answers one fragment rather than a list of
 * things, so there is no page to be on — which is why its failure set carries none of the
 * continuation categories that every paged command shares.</p>
 *
 * @param fragmentPath the fragment to read
 * @param variationName which variation of it to read
 */
public record ReadContentFragmentCommand(String fragmentPath, String variationName) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "read_content_fragment";

    /** The variation every fragment has, which is what an omitted name asks for. */
    public static final String MASTER_VARIATION = "master";

    /** The member the fragment's address is carried in. */
    public static final String FRAGMENT_PATH = "fragment_path";

    /** The member the variation's name is carried in. */
    public static final String VARIATION_NAME = "variation_name";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS = List.of(FRAGMENT_PATH, VARIATION_NAME);

    /**
     * The member a caller has to send.
     *
     * <p>The variation is not one of them. The client's own schema makes it optional, and an
     * omitted variation means the master — the one variation every fragment has. Requiring it
     * would refuse the commonest question this command is asked.</p>
     */
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
        /** The variation names nothing, and this command chooses none for a caller. */
        VARIATION_EMPTY,
        /** The variation's name is longer than this deployment records. */
        VARIATION_TOO_LONG
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(ReadContentFragmentCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds a variation's name
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object with two members");
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
                    absent.get() + " is required; this command chooses no fragment for a caller");
        }
        if (!(mapping.member(FRAGMENT_PATH).orElseThrow() instanceof final DocumentValue.Text fragment)
                || fragment.value().isEmpty() || fragment.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    FRAGMENT_PATH + " is an absolute path beginning at the root");
        }
        return variationed(fragment.value(), mapping, contract);
    }

    /**
     * The variation one argument names, which is the master where it names none.
     *
     * <p>Every fragment has a master and most callers want it, so an omitted name is that rather
     * than a refusal. A name that is present and empty is still refused: somebody who sent the
     * member meant to name something.</p>
     *
     * @param fragment the fragment's address
     * @param mapping the argument document
     * @param contract the authenticated contract, which bounds a variation's name
     * @return the command, or the one reason there is none
     */
    private static Outcome variationed(String fragment, DocumentValue.Mapping mapping,
                                       AgentContract contract) {
        final Optional<DocumentValue> named = mapping.member(VARIATION_NAME);
        if (named.isEmpty()) {
            return new Held(new ReadContentFragmentCommand(fragment, MASTER_VARIATION));
        }
        if (!(named.orElseThrow() instanceof final DocumentValue.Text variation)
                || variation.value().isBlank()) {
            return new Refused(Refusal.VARIATION_EMPTY, VARIATION_NAME + " was sent and names"
                    + " nothing; a caller who wants the master sends no variation at all");
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_VARIATION_NAME_BYTES);
        if (variation.value().length() > bound) {
            return new Refused(Refusal.VARIATION_TOO_LONG, VARIATION_NAME + " is longer than the "
                    + bound + " a variation's name may be");
        }
        return new Held(new ReadContentFragmentCommand(fragment, variation.value()));
    }
}
