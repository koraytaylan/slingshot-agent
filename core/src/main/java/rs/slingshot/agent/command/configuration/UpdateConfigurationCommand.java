// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.ConfigurationValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which configuration to change, what to set on it, and what to take away.
 *
 * <p>Two lists, for the same reason a page update has two: a property in neither is left exactly as
 * it was. A configuration is written by several things at once — a deployment, an installer, an
 * operator — and a caller who sent a partial view and had the rest treated as removals would
 * silently undo whatever the last of those did.</p>
 *
 * <p>Every assignment carries its own type and cardinality. They are not recoverable from the
 * values, and getting either wrong produces a configuration that reads back fine and fails at the
 * next restart, which is the worst kind of wrong: the failure and the cause are hours apart.</p>
 *
 * @param persistentIdentifier what the platform calls the configuration
 * @param assignments what to set, by property name
 * @param removedPropertyKeys what to take away
 */
public record UpdateConfigurationCommand(String persistentIdentifier,
                                         SequencedMap<String, ConfigurationValue> assignments,
                                         List<String> removedPropertyKeys) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "update_open_service_gateway_initiative_configuration";

    /** The member the identifier is carried in. */
    public static final String PERSISTENT_IDENTIFIER = "persistent_identifier";

    /** The member the assignments are carried in. */
    public static final String ASSIGNMENTS = "assignments";

    /** The member the removals are carried in. */
    public static final String REMOVED_PROPERTY_KEYS = "removed_property_keys";

    /** Every member this command's argument has, nested ones included. */
    public static final List<String> MEMBERS = List.of(ASSIGNMENTS,
            ConfigurationValue.CARDINALITY, PERSISTENT_IDENTIFIER, REMOVED_PROPERTY_KEYS,
            ConfigurationValue.TYPE, ConfigurationValue.VALUE, ConfigurationValue.VALUES);

    /** The member a caller has to send; a change that names nothing changes nothing. */
    public static final List<String> REQUIRED = List.of(PERSISTENT_IDENTIFIER);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** The identifier is absent, and this command chooses no configuration. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The identifier is empty, or longer than one may be. */
        IDENTIFIER_REJECTED,
        /** The assignments are not a document of them. */
        ASSIGNMENTS_REJECTED,
        /** An assignment names a type or a cardinality this contract does not write. */
        VALUE_REJECTED,
        /** More was named than the contract allows. */
        TOO_MANY,
        /** A property name is both set and removed, and the two orders disagree. */
        SET_AND_REMOVED,
        /** The removals are not a list of names. */
        REMOVALS_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(UpdateConfigurationCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the property rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** Holds a change whose lists nothing can change afterwards. */
    public UpdateConfigurationCommand {
        assignments = new LinkedHashMap<>(assignments);
        removedPropertyKeys = List.copyOf(removedPropertyKeys);
    }

    /**
     * What to set.
     *
     * @return the assignments, which nothing may add to
     */
    @Override
    public SequencedMap<String, ConfigurationValue> assignments() {
        return Collections.unmodifiableSequencedMap(assignments);
    }

    /**
     * Whether this change would leave the configuration exactly as it is.
     *
     * @return whether it would
     */
    public boolean isEmpty() {
        return assignments.isEmpty() && removedPropertyKeys.isEmpty();
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier and both lists
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming a configuration and what to change about it");
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
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final long bound =
                contract.value(ContractLimit.MAXIMUM_CONFIGURATION_PERSISTENT_IDENTIFIER_BYTES);
        if (!(mapping.member(PERSISTENT_IDENTIFIER).orElseThrow()
                instanceof final DocumentValue.Text identifier)
                || identifier.value().isEmpty() || identifier.value().length() > bound) {
            return new Refused(Refusal.IDENTIFIER_REJECTED, PERSISTENT_IDENTIFIER + " is what the"
                    + " platform calls one configuration: not empty, and within the " + bound
                    + " an identifier may be");
        }
        final Optional<Refused> broken = removalRefusal(mapping, contract);
        return broken.isPresent()
                ? broken.orElseThrow()
                : assigned(identifier.value(), namesIn(mapping), mapping, contract);
    }

    /**
     * Why the removals are not a list this command takes, where they are not.
     *
     * @param mapping the argument document
     * @param contract the authenticated contract, which bounds how many may be named
     * @return the refusal, or nothing where they are a list of names within the bound
     */
    private static Optional<Refused> removalRefusal(DocumentValue.Mapping mapping,
                                                    AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(REMOVED_PROPERTY_KEYS);
        if (asked.isEmpty()) {
            return Optional.empty();
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence items)
                || items.items().stream().anyMatch(item -> !(item instanceof DocumentValue.Text))) {
            return Optional.of(new Refused(Refusal.REMOVALS_REJECTED,
                    REMOVED_PROPERTY_KEYS + " is a list of property names"));
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_CONFIGURATION_LOOKUP_MATCHES);
        return items.items().size() > bound
                ? Optional.of(new Refused(Refusal.TOO_MANY, items.items().size() + " removals is"
                        + " more than the " + bound + " one change may name"))
                : Optional.empty();
    }

    private static List<String> namesIn(DocumentValue.Mapping mapping) {
        return mapping.member(REMOVED_PROPERTY_KEYS)
                .filter(DocumentValue.Sequence.class::isInstance)
                .map(value -> ((DocumentValue.Sequence) value).items().stream()
                        .map(item -> ((DocumentValue.Text) item).value())
                        .toList())
                .orElseGet(List::of);
    }

    private static Outcome assigned(String identifier, List<String> removed,
                                    DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(ASSIGNMENTS);
        if (asked.isEmpty()) {
            return new Held(new UpdateConfigurationCommand(identifier, new LinkedHashMap<>(),
                    removed));
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Mapping assignments)) {
            return new Refused(Refusal.ASSIGNMENTS_REJECTED,
                    ASSIGNMENTS + " names properties by name, each with what to set it to");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_CONFIGURATION_LOOKUP_MATCHES);
        if (assignments.members().size() > bound) {
            return new Refused(Refusal.TOO_MANY, assignments.members().size() + " assignments is"
                    + " more than the " + bound + " one configuration holds");
        }
        return collected(identifier, removed, assignments, contract);
    }

    private static Outcome collected(String identifier, List<String> removed,
                                     DocumentValue.Mapping assignments, AgentContract contract) {
        final SequencedMap<String, ConfigurationValue> held = new LinkedHashMap<>();
        final List<String> both = new ArrayList<>();
        for (final var assignment : assignments.members().entrySet()) {
            if (removed.contains(assignment.getKey())) {
                both.add(assignment.getKey());
                continue;
            }
            final Optional<ConfigurationValue> value = valueOf(assignment.getValue(), contract);
            if (value.isEmpty()) {
                return new Refused(Refusal.VALUE_REJECTED, assignment.getKey() + " is set to a"
                        + " value carrying its own type and cardinality, from "
                        + ConfigurationValue.TYPES + " and "
                        + ConfigurationValue.Cardinality.spellings());
            }
            held.put(assignment.getKey(), value.orElseThrow());
        }
        return both.isEmpty()
                ? new Held(new UpdateConfigurationCommand(identifier, held, removed))
                : new Refused(Refusal.SET_AND_REMOVED, both.getFirst() + " is both set and removed."
                        + " Set-then-remove and remove-then-set leave different configurations, so"
                        + " this is refused rather than resolved in an order nobody chose.");
    }

    /**
     * One assignment read as a typed value.
     *
     * @param assigned what the caller sent for one property
     * @param contract the authenticated contract, which bounds the values
     * @return the value, or nothing where that is not one this contract writes
     */
    public static Optional<ConfigurationValue> valueOf(DocumentValue assigned,
                                                       AgentContract contract) {
        if (!(assigned instanceof final DocumentValue.Mapping value)) {
            return Optional.empty();
        }
        final Optional<String> type = value.member(ConfigurationValue.TYPE)
                .filter(DocumentValue.Text.class::isInstance)
                .map(held -> ((DocumentValue.Text) held).value())
                .filter(ConfigurationValue.TYPES::contains);
        final Optional<ConfigurationValue.Cardinality> cardinality =
                value.member(ConfigurationValue.CARDINALITY)
                        .filter(DocumentValue.Text.class::isInstance)
                        .flatMap(held -> ConfigurationValue.Cardinality.named(
                                ((DocumentValue.Text) held).value()));
        if (type.isEmpty() || cardinality.isEmpty()) {
            return Optional.empty();
        }
        return valuesOf(value, cardinality.orElseThrow(), contract)
                .map(values -> new ConfigurationValue(type.orElseThrow(),
                        cardinality.orElseThrow(), values));
    }

    private static Optional<List<String>> valuesOf(DocumentValue.Mapping value,
                                                   ConfigurationValue.Cardinality cardinality,
                                                   AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_CONFIGURATION_SCALAR_STRING_BYTES);
        if (cardinality.isSingle()) {
            return value.member(ConfigurationValue.VALUE)
                    .flatMap(UpdateConfigurationCommand::rendered)
                    .filter(held -> held.length() <= bound)
                    .map(List::of);
        }
        final Optional<DocumentValue> items = value.member(ConfigurationValue.VALUES);
        if (items.isEmpty() || !(items.orElseThrow() instanceof final DocumentValue.Sequence held)
                || held.items().size()
                        > contract.value(ContractLimit.MAXIMUM_CONFIGURATION_SEQUENCE_ITEMS)) {
            return Optional.empty();
        }
        final List<String> values = held.items().stream()
                .map(UpdateConfigurationCommand::rendered)
                .flatMap(Optional::stream)
                .filter(item -> item.length() <= bound)
                .toList();
        return values.size() == held.items().size() ? Optional.of(values) : Optional.empty();
    }

    /**
     * One value as the text a configuration stores.
     *
     * <p>The client sends text or a flag and nothing else, which is why this reads exactly those
     * two: the type member says what the platform is to make of the text, and a number arriving as
     * a number rather than as text would be a second way of saying the same thing.</p>
     *
     * @param item what the caller sent
     * @return the text, or nothing where it is neither
     */
    private static Optional<String> rendered(DocumentValue item) {
        if (item instanceof final DocumentValue.Text text) {
            return Optional.of(text.value());
        }
        return item instanceof final DocumentValue.Flag flag
                ? Optional.of(String.valueOf(flag.value() == DocumentValue.Truth.TRUE))
                : Optional.empty();
    }
}
