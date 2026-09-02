// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.ReferencePolicy;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which fragment to remove, and what to do about the things pointing at it.
 *
 * <p>One reader for both kinds, because the argument is the same argument: an address and a policy.
 * The two commands stay separate everywhere it matters — their own rows, their own bounds, their
 * own answers — and share the one place where writing it twice would only give two chances to
 * spell it differently.</p>
 *
 * <p>The policy is required and has no default. A fragment is reused from places its author never
 * sees; that is what a fragment is for. Choosing on the caller's behalf what happens to those
 * places is not a default, it is a guess about somebody else's site.</p>
 *
 * @param fragmentPath the fragment to remove
 * @param referencePolicy what to do about the things pointing at it
 */
public record FragmentDeletion(String fragmentPath, ReferencePolicy referencePolicy) {

    /** The wire name of the command that removes a content fragment. */
    public static final String CONTENT_WIRE_NAME = "delete_content_fragment";

    /** The wire name of the command that removes an experience fragment. */
    public static final String EXPERIENCE_WIRE_NAME = "delete_experience_fragment";

    /** The member the fragment's address is carried in. */
    public static final String FRAGMENT_PATH = "fragment_path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(FRAGMENT_PATH, ReferencePolicy.ARGUMENT_MEMBER);

    /** The members a caller has to send, which is both: neither has a defensible default. */
    public static final List<String> REQUIRED = MEMBERS;

    /** Why an argument is not one these commands take. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The fragment is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The reference policy is not one of the two there are. */
        UNKNOWN_REFERENCE_POLICY
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument these commands take.
     *
     * @param command what was asked
     */
    public record Held(FragmentDeletion command) implements Outcome {
    }

    /**
     * One they do not.
     *
     * @param refusal why they do not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object naming a fragment"
                    + " and what to do about the things pointing at it");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; a fragment is"
                    + " reused from places its author never sees, so what happens to those is not"
                    + " something this side may choose");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(FRAGMENT_PATH).orElseThrow()
                instanceof final DocumentValue.Text fragment)
                || fragment.value().isEmpty() || fragment.value().charAt(0) != '/'
                || fragment.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    FRAGMENT_PATH + " is an absolute path beginning at the root");
        }
        if (!(mapping.member(ReferencePolicy.ARGUMENT_MEMBER).orElseThrow()
                instanceof final DocumentValue.Text spelled)) {
            return new Refused(Refusal.UNKNOWN_REFERENCE_POLICY,
                    ReferencePolicy.ARGUMENT_MEMBER + " is one of " + ReferencePolicy.spellings());
        }
        return ReferencePolicy.named(spelled.value())
                .<Outcome>map(policy -> new Held(new FragmentDeletion(fragment.value(), policy)))
                .orElseGet(() -> new Refused(Refusal.UNKNOWN_REFERENCE_POLICY, spelled.value()
                        + " is not a policy this contract has; they are "
                        + ReferencePolicy.spellings()));
    }
}
