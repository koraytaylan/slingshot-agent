// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which request address a caller wants resolved, and whether they want to see the rules.
 *
 * <p>Resolving is not the mirror of mapping and this argument is why. An address is resolved
 * <em>under a request</em> — the host it arrived on, the selectors and extension on it — so the
 * whole request address is the argument. Mapping has no request to happen under, which is why the
 * two commands take different arguments rather than one argument and a direction.</p>
 *
 * @param requestAddress the request address to resolve
 * @param trace whether the rules the resolution went through travel back with the answer
 */
public record ResolveResourcePathCommand(String requestAddress, TraceDisclosure trace) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "resolve_resource_path";

    /** The member the request address is carried in. */
    public static final String REQUEST_ADDRESS = "request_address";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(REQUEST_ADDRESS, TraceDisclosure.ARGUMENT_MEMBER);

    /** The members a caller has to send, which is both: neither has a defensible default. */
    public static final List<String> REQUIRED = MEMBERS;

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The request address is empty, or longer than the contract allows. */
        REQUEST_ADDRESS_REJECTED,
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
    public record Held(ResolveResourcePathCommand command) implements Outcome {
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
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object with a request"
                    + " address in it");
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
                    + " chooses neither an address nor how much to say about it for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(REQUEST_ADDRESS).orElseThrow()
                instanceof final DocumentValue.Text address) || address.value().isBlank()) {
            return new Refused(Refusal.REQUEST_ADDRESS_REJECTED,
                    REQUEST_ADDRESS + " is the request address to resolve, and it is not empty");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_REQUEST_ADDRESS_BYTES);
        if (address.value().length() > bound) {
            return new Refused(Refusal.REQUEST_ADDRESS_REJECTED, REQUEST_ADDRESS
                    + " is longer than the " + bound + " a request address may be");
        }
        final Optional<TraceDisclosure> trace =
                TraceDisclosure.of(mapping.member(TraceDisclosure.ARGUMENT_MEMBER).orElseThrow());
        return trace.<Outcome>map(asked ->
                        new Held(new ResolveResourcePathCommand(address.value(), asked)))
                .orElseGet(() -> new Refused(Refusal.TRACE_NOT_A_FLAG,
                        TraceDisclosure.ARGUMENT_MEMBER + " is true or false"));
    }
}
