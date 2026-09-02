// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which repository path a caller wants mapped outward, and under which host.
 *
 * <p>The authority is optional and it is the whole reason this is not the mirror of resolving. One
 * repository path publishes as several addresses — one per host the instance answers on — so a
 * caller who names a host is asking what the path looks like to somebody arriving there, and a
 * caller who names none is asking what it looks like by default.</p>
 *
 * @param repositoryPath the path to map
 * @param requestAuthority the host to map it for, empty where the caller named none
 * @param trace whether the rules the mapping went through travel back with the answer
 */
public record MapResourcePathCommand(String repositoryPath, String requestAuthority,
                                     TraceDisclosure trace) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "map_resource_path";

    /** The member the path to map is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the host to map for is carried in, where the caller names one. */
    public static final String REQUEST_AUTHORITY = "request_authority";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(REPOSITORY_PATH, REQUEST_AUTHORITY, TraceDisclosure.ARGUMENT_MEMBER);

    /** The members a caller has to send; the host is the one with a defensible absence. */
    public static final List<String> REQUIRED =
            List.of(REPOSITORY_PATH, TraceDisclosure.ARGUMENT_MEMBER);

    /** Where a caller named no host, which asks what the path publishes as by default. */
    public static final String NO_AUTHORITY = "";

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The path is not an absolute repository path, or is longer than the contract allows. */
        NOT_AN_ABSOLUTE_PATH,
        /** The host was sent and names nothing. */
        AUTHORITY_EMPTY,
        /** The trace member is not a flag. */
        TRACE_NOT_A_FLAG
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(MapResourcePathCommand command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds the path
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object with a repository path in it");
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
                    + " chooses neither a path nor how much to say about it for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(REPOSITORY_PATH).orElseThrow()
                instanceof final DocumentValue.Text path)
                || path.value().isEmpty() || path.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    REPOSITORY_PATH + " is an absolute path beginning at the root");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES);
        if (path.value().length() > bound) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, REPOSITORY_PATH
                    + " is longer than the " + bound + " a repository path may be");
        }
        return authorised(path.value(), mapping);
    }

    private static Outcome authorised(String path, DocumentValue.Mapping mapping) {
        final Optional<DocumentValue> named = mapping.member(REQUEST_AUTHORITY);
        if (named.isPresent() && (!(named.orElseThrow() instanceof final DocumentValue.Text host)
                || host.value().isBlank())) {
            return new Refused(Refusal.AUTHORITY_EMPTY, REQUEST_AUTHORITY + " was sent and names"
                    + " nothing; a caller who wants the default host sends no authority at all");
        }
        final String authority = named
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(NO_AUTHORITY);
        final Optional<TraceDisclosure> trace =
                TraceDisclosure.of(mapping.member(TraceDisclosure.ARGUMENT_MEMBER).orElseThrow());
        return trace.<Outcome>map(asked ->
                        new Held(new MapResourcePathCommand(path, authority, asked)))
                .orElseGet(() -> new Refused(Refusal.TRACE_NOT_A_FLAG,
                        TraceDisclosure.ARGUMENT_MEMBER + " is true or false"));
    }
}
