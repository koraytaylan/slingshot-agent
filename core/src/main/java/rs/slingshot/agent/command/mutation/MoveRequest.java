// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which thing to move, where to, and whether the links that point at it come too.
 *
 * <p>Read once for the two commands that move something, because moving a page and moving an asset
 * are the same question asked about different things: the same three members, the same bounds, and
 * the same refusal for a destination inside the source. Two readers would be two chances for that
 * last one — the mistake with the worst aftermath — to come to mean something slightly different.
 * </p>
 *
 * <p>Moving is where links break. Whether they are followed is the caller's to say and has no
 * default, because both answers are right somewhere: something moving inside a site wants its links
 * brought along, and something being lifted out of one wants them left where they are so that
 * whoever owns them notices.</p>
 *
 * <p>A destination inside the source is its own refusal. It is the mistake that produces the most
 * confusing repository state — a page containing the place it was moved to — and it is the one a
 * path comparison catches for nothing.</p>
 *
 * @param sourcePath the thing to move
 * @param destinationPath where it goes
 * @param adjustReferences whether the things pointing at it are pointed at the new address
 */
public record MoveRequest(String sourcePath, String destinationPath,
                              ReferenceAdjustment adjustReferences) {

    /** The member the page to move is carried in. */
    public static final String SOURCE_PATH = "source_path";

    /** The member the address it goes to is carried in. */
    public static final String DESTINATION_PATH = "destination_path";

    /** The member saying whether the things pointing at it come too. */
    public static final String ADJUST_REFERENCES = "adjust_references";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(ADJUST_REFERENCES, DESTINATION_PATH, SOURCE_PATH);

    /** The members a caller has to send, which is all three: none has a defensible default. */
    public static final List<String> REQUIRED = MEMBERS;

    /**
     * Whether a move takes the links that point at what moved.
     *
     * <p>Named rather than carried as a bare boolean inside this build, though it crosses the wire
     * as one. A parameter called {@code adjust} that is either true or false reads identically
     * whichever it is at the call site; these two do not.</p>
     */
    public enum ReferenceAdjustment {
        /** Point them at the new address. */
        FOLLOWED,
        /** Leave them where they are, so whoever owns them finds out. */
        LEFT
    }

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** An address is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The adjustment member is not a flag. */
        ADJUSTMENT_NOT_A_FLAG,
        /** The destination is inside the source, or is the source. */
        DESTINATION_INSIDE_SOURCE
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(MoveRequest command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds both addresses
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming a page, where it goes, and what to do about"
                            + " the links pointing at it");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; leaving links"
                    + " broken and rewriting them are both right somewhere, and this side cannot"
                    + " tell which somewhere this is");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> source = absolute(mapping, SOURCE_PATH, contract);
        final Optional<String> destination = absolute(mapping, DESTINATION_PATH, contract);
        if (source.isEmpty() || destination.isEmpty()) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, SOURCE_PATH + " and "
                    + DESTINATION_PATH + " are absolute paths beginning at the root, within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)
                    + " a path may be");
        }
        if (destination.orElseThrow().equals(source.orElseThrow())
                || destination.orElseThrow().startsWith(source.orElseThrow() + "/")) {
            return new Refused(Refusal.DESTINATION_INSIDE_SOURCE, destination.orElseThrow()
                    + " is inside " + source.orElseThrow() + ", or is it. Nothing can contain the"
                    + " place it was moved to, and that is the mistake with the most confusing"
                    + " aftermath.");
        }
        if (!(mapping.member(ADJUST_REFERENCES).orElseThrow()
                instanceof final DocumentValue.Flag adjust)) {
            return new Refused(Refusal.ADJUSTMENT_NOT_A_FLAG,
                    ADJUST_REFERENCES + " is true or false");
        }
        return new Held(new MoveRequest(source.orElseThrow(), destination.orElseThrow(),
                adjust.value() == DocumentValue.Truth.TRUE
                        ? ReferenceAdjustment.FOLLOWED : ReferenceAdjustment.LEFT));
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
}
