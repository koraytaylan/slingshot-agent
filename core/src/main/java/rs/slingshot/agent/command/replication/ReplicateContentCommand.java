// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What to offer to replication: one address, and whether what is beneath it comes too.
 *
 * <p>Both members are required. The scope has no default because the two answers differ by four
 * orders of magnitude in what they do to a publish queue, and a caller who meant one page and got
 * their whole site offered has done something they cannot take back by asking again.</p>
 *
 * @param path what to offer
 * @param scope whether what is beneath it comes too
 */
public record ReplicateContentCommand(String path, SubtreeScope scope) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "replicate_content";

    /** The member the address is carried in. */
    public static final String PATH = "path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS = List.of(PATH, SubtreeScope.ARGUMENT_MEMBER);

    /** The members a caller has to send, which is both. */
    public static final List<String> REQUIRED = MEMBERS;

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The address is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The scope is not a flag. */
        SCOPE_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(ReplicateContentCommand command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds the address
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming what to offer and how much of it");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; offering one"
                    + " page and offering a whole site are not something this side may choose"
                    + " between on a caller's behalf");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(PATH).orElseThrow() instanceof final DocumentValue.Text path)
                || path.value().isEmpty() || path.value().charAt(0) != '/'
                || path.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    PATH + " is an absolute path beginning at the root");
        }
        return SubtreeScope.of(mapping.member(SubtreeScope.ARGUMENT_MEMBER).orElseThrow())
                .<Outcome>map(scope -> new Held(new ReplicateContentCommand(path.value(), scope)))
                .orElseGet(() -> new Refused(Refusal.SCOPE_REJECTED, SubtreeScope.ARGUMENT_MEMBER
                        + " says whether what is beneath this address comes too, and it is one of"
                        + " the two things a flag is"));
    }
}
