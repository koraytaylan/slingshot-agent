// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One configuration, named.
 *
 * <p>Shared by the command that reads a configuration and the one that removes it, because the
 * argument is the same argument: an identifier and nothing else. The two stay separate everywhere
 * it matters — their own rows, their own failure sets, and one of them passing through the control
 * gate while the other does not — and share the one place where writing it twice would only give
 * two chances to spell the member differently.</p>
 *
 * @param persistentIdentifier what the platform calls the configuration
 */
public record ConfigurationIdentifierCommand(String persistentIdentifier) {

    /** The wire name of the command that reads one configuration. */
    public static final String INSPECT_WIRE_NAME =
            "inspect_open_service_gateway_initiative_configuration";

    /** The wire name of the command that removes one. */
    public static final String DELETE_WIRE_NAME =
            "delete_open_service_gateway_initiative_configuration";

    /** The member the identifier is carried in. */
    public static final String PERSISTENT_IDENTIFIER = "persistent_identifier";

    /** Every member this command's argument has, and there is no second. */
    public static final List<String> MEMBERS = List.of(PERSISTENT_IDENTIFIER);

    /** The member a caller has to send, which is the only one there is. */
    public static final List<String> REQUIRED = MEMBERS;

    /** Why an argument is not one these commands take. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** The identifier is absent, and neither command chooses a configuration for a caller. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The identifier is empty, or longer than one may be. */
        IDENTIFIER_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument these commands take.
     *
     * @param command what was asked
     */
    public record Held(ConfigurationIdentifierCommand command) implements Outcome {
    }

    /**
     * One they do not.
     *
     * @param refusal why they do not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming one configuration");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(PERSISTENT_IDENTIFIER).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    PERSISTENT_IDENTIFIER + " is required; this command chooses no configuration");
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_CONFIGURATION_PERSISTENT_IDENTIFIER_BYTES);
        if (!(mapping.member(PERSISTENT_IDENTIFIER).orElseThrow()
                instanceof final DocumentValue.Text identifier)
                || identifier.value().isEmpty() || identifier.value().length() > bound) {
            return new Refused(Refusal.IDENTIFIER_REJECTED, PERSISTENT_IDENTIFIER + " is what the"
                    + " platform calls one configuration: not empty, and within the " + bound
                    + " an identifier may be");
        }
        return new Held(new ConfigurationIdentifierCommand(identifier.value()));
    }
}
