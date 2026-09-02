// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which configurations to list, and which page of them.
 *
 * <p>The only filter is a prefix on the identifier, and that is deliberate rather than a
 * limitation. An operator looking for a configuration knows roughly what it is called — the
 * identifier is the service's own class name most of the time — and every other way of narrowing
 * this would mean reading values to decide what matches, which is the one thing a listing across a
 * whole instance must not do.</p>
 *
 * <p>A prefix nobody sent means every configuration, which is the ordinary case: an operator
 * exploring an environment they did not build starts by seeing what is there.</p>
 *
 * @param prefix what an identifier must begin with, which is {@link #EVERY_CONFIGURATION} for all
 * @param window which page is wanted
 */
public record FindConfigurationsCommand(String prefix, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_open_service_gateway_initiative_configurations";

    /** The member the identifier prefix is carried in. */
    public static final String PERSISTENT_IDENTIFIER_PREFIX = "persistent_identifier_prefix";

    /** What the prefix says when the caller named none, which matches every configuration. */
    public static final String EVERY_CONFIGURATION = "";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(PERSISTENT_IDENTIFIER_PREFIX, ResultWindow.ARGUMENT_MEMBER);

    /** The members a caller has to send, which is none: both have a defensible default. */
    public static final List<String> REQUIRED = List.of();

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The prefix is empty, or longer than an identifier may be. */
        PREFIX_REJECTED,
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(FindConfigurationsCommand command) implements Outcome {
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
     * Whether one identifier is in this search.
     *
     * @param persistentIdentifier what the platform calls a configuration
     * @return whether it begins with this search's prefix
     */
    public boolean matches(String persistentIdentifier) {
        return persistentIdentifier.startsWith(prefix);
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the prefix and the window
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which configurations and which page of them");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument. The only filter"
                            + " is a prefix on the identifier: every other way of narrowing this"
                            + " would mean reading values to decide what matches, which is the one"
                            + " thing a listing across a whole instance must not do.");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(PERSISTENT_IDENTIFIER_PREFIX);
        final long bound =
                contract.value(ContractLimit.MAXIMUM_CONFIGURATION_PERSISTENT_IDENTIFIER_BYTES);
        if (asked.isPresent()
                && (!(asked.orElseThrow() instanceof final DocumentValue.Text text)
                        || text.value().isEmpty() || text.value().length() > bound)) {
            return new Refused(Refusal.PREFIX_REJECTED, PERSISTENT_IDENTIFIER_PREFIX + " is text"
                    + " an identifier begins with: not empty, and within the " + bound
                    + " an identifier may be. Leave it out to see every configuration.");
        }
        final String prefix = asked
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(EVERY_CONFIGURATION);
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new FindConfigurationsCommand(prefix,
                        ((ResultWindow.Held) window).window()));
    }
}
