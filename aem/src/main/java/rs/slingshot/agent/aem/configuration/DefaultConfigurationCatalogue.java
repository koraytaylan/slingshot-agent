// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Supplier;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.metatype.AttributeDefinition;
import org.osgi.service.metatype.MetaTypeInformation;
import org.osgi.service.metatype.MetaTypeService;
import org.osgi.service.metatype.ObjectClassDefinition;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.command.platform.ConfigurationValue;
import rs.slingshot.agent.command.platform.ValueDisclosure;

/**
 * The platform's own configuration admin, answering the four configuration commands.
 *
 * <p>Which property is a secret is the Meta Type Service's answer, asked of the bundle that
 * describes the configuration, and a property it does not describe is treated as one: its value
 * is never converted, and the answer carries only its name and that evidence. The two changes
 * reach here only after the deployment's control gate has permitted them.</p>
 */
public final class DefaultConfigurationCatalogue implements ConfigurationCatalogue {

    /** What a configuration admin that could not be read is reported as. */
    private static final String LOOKUP_FAILED = "configuration_lookup_failed";

    /** What a search that would examine more than it may is reported as. */
    private static final String LOOKUP_BUDGET_EXCEEDED = "configuration_lookup_budget_exceeded";

    /** What an identifier naming no configuration is reported as when asked to change it. */
    private static final String LOOKUP_MISMATCH = "configuration_lookup_mismatch";

    /** What an identifier the configuration admin holds twice is reported as. */
    private static final String LOOKUP_AMBIGUOUS = "configuration_lookup_ambiguous";

    /** What a value that does not read as its type is reported as. */
    private static final String VALUE_MALFORMED = "configuration_value_malformed";

    /** What a property holding a type no configuration command speaks is reported as. */
    private static final String VALUE_UNSUPPORTED = "configuration_value_unsupported";

    /** The property the configuration admin keeps a configuration's identifier in. */
    private static final String PID = "service.pid";

    /** The location that binds a new configuration to no bundle in particular. */
    private static final String ANY_LOCATION = "?";

    /** What a property whose value is not read carries, which is never reported. */
    private static final ConfigurationValue UNREAD = new ConfigurationValue("string",
            ConfigurationValue.Cardinality.SCALAR, List.of());

    private final ConfigurationAdmin admin;
    private final MetaTypeService metatypes;
    /** Reads every bundle the framework holds, which is where descriptions are looked for. */
    private final Supplier<Bundle[]> installed;

    /**
     * Holds the catalogue the platform's configuration admin and meta type service answer.
     *
     * @param installed reads every bundle the framework holds
     * @param admin the configuration admin
     * @param metatypes the meta type service
     */
    DefaultConfigurationCatalogue(Supplier<Bundle[]> installed, ConfigurationAdmin admin,
                                  MetaTypeService metatypes) {
        this.installed = installed;
        this.admin = admin;
        this.metatypes = metatypes;
    }

    @Override
    public Outcome find(String prefix, long budget) {
        final List<Configuration> found;
        try {
            found = listed("(" + PID + "=" + escaped(prefix) + "*)");
        } catch (final IOException | InvalidSyntaxException | SecurityException failed) {
            return new Failed(LOOKUP_FAILED, "the configuration admin could not be read: "
                    + failed.getMessage());
        }
        if (found.size() > budget) {
            return new Failed(LOOKUP_BUDGET_EXCEEDED, found.size() + " configurations begin with"
                    + " the prefix, more than the " + budget + " one search may examine");
        }
        return new Listed(found.stream()
                .sorted(java.util.Comparator.comparing(Configuration::getPid))
                .map(configuration -> new Entry(configuration.getPid(),
                        Optional.ofNullable(configuration.getFactoryPid())
                                .orElse(NOT_FROM_A_FACTORY),
                        propertiesOf(configuration).size(),
                        configuration.getBundleLocation() == null ? Binding.UNBOUND
                                : Binding.BOUND_TO_A_BUNDLE_LOCATION))
                .toList());
    }

    @Override
    public Outcome inspect(String persistentIdentifier) {
        final Optional<Configuration> held;
        try {
            held = one(persistentIdentifier);
        } catch (final Ambiguous ambiguous) {
            return ambiguous.failed();
        } catch (final IOException | InvalidSyntaxException | SecurityException failed) {
            return new Failed(LOOKUP_FAILED, "the configuration admin could not be read: "
                    + failed.getMessage());
        }
        if (held.isEmpty()) {
            return new Inspected(Presence.ABSENT, List.of());
        }
        final Configuration configuration = held.get();
        final Map<String, AttributeDefinition> described = described(configuration);
        final List<Property> answered = new ArrayList<>();
        for (final Map.Entry<String, Object> property : propertiesOf(configuration).entrySet()) {
            final String name = property.getKey();
            final AttributeDefinition definition = described.get(name);
            if (definition == null) {
                answered.add(new Property(name, ValueDisclosure.Evidence.UNAVAILABLE, UNREAD));
            } else if (definition.getType() == AttributeDefinition.PASSWORD) {
                answered.add(new Property(name, ValueDisclosure.Evidence.PASSWORD, UNREAD));
            } else {
                final Optional<ConfigurationValue> value = PropertyValues.read(property.getValue());
                if (value.isEmpty()) {
                    return new Failed(VALUE_UNSUPPORTED, name + " holds a value of a type no"
                            + " configuration command speaks");
                }
                answered.add(new Property(name, ValueDisclosure.Evidence.NON_PASSWORD,
                        value.get()));
            }
        }
        return new Inspected(Presence.PRESENT, answered);
    }

    @Override
    public Outcome apply(String persistentIdentifier,
                         SequencedMap<String, ConfigurationValue> assignments,
                         List<String> removedPropertyKeys) {
        final Map<String, Object> written = new LinkedHashMap<>();
        try {
            for (final Map.Entry<String, ConfigurationValue> assignment : assignments.entrySet()) {
                written.put(assignment.getKey(), PropertyValues.written(assignment.getValue()));
            }
        } catch (final IllegalArgumentException malformed) {
            return new Failed(VALUE_MALFORMED, malformed.getMessage());
        }
        try {
            final Optional<Configuration> held = one(persistentIdentifier);
            final Configuration configuration = held.isPresent() ? held.get()
                    : admin.getConfiguration(persistentIdentifier, ANY_LOCATION);
            final Map<String, Object> properties = propertiesOf(configuration);
            properties.putAll(written);
            removedPropertyKeys.forEach(properties::remove);
            configuration.update(FrameworkUtil.asDictionary(properties));
            return new Changed(written.size() + removedPropertyKeys.size(),
                    originOf(configuration));
        } catch (final Ambiguous ambiguous) {
            return ambiguous.failed();
        } catch (final IOException | InvalidSyntaxException | IllegalStateException
                       | SecurityException failed) {
            return new Failed(LOOKUP_FAILED, "the configuration admin refused the change: "
                    + failed.getMessage());
        }
    }

    @Override
    public Outcome erase(String persistentIdentifier) {
        try {
            final Optional<Configuration> held = one(persistentIdentifier);
            if (held.isEmpty()) {
                return new Failed(LOOKUP_MISMATCH, persistentIdentifier + " names no"
                        + " configuration the configuration admin holds");
            }
            final Origin origin = originOf(held.get());
            final long keys = propertiesOf(held.get()).size();
            held.get().delete();
            return new Changed(keys, origin);
        } catch (final Ambiguous ambiguous) {
            return ambiguous.failed();
        } catch (final IOException | InvalidSyntaxException | IllegalStateException
                       | SecurityException failed) {
            return new Failed(LOOKUP_FAILED, "the configuration admin refused the removal: "
                    + failed.getMessage());
        }
    }

    private List<Configuration> listed(String filter) throws IOException, InvalidSyntaxException {
        return Optional.ofNullable(admin.listConfigurations(filter))
                .map(Arrays::asList)
                .orElseGet(List::of);
    }

    private Optional<Configuration> one(String persistentIdentifier)
            throws IOException, InvalidSyntaxException, Ambiguous {
        final List<Configuration> found = listed("(" + PID + "=" + escaped(persistentIdentifier)
                + ")");
        if (found.size() > 1) {
            throw new Ambiguous(persistentIdentifier);
        }
        return found.stream().findFirst();
    }

    /**
     * What the Meta Type Service says about one configuration's properties, by property name.
     *
     * <p>Asked of the bundle the configuration is bound to first and then of every bundle that
     * describes it, because a configuration written before its bundle was installed is bound to
     * nothing and is still described by that bundle.</p>
     */
    private Map<String, AttributeDefinition> described(Configuration configuration) {
        final String factory = configuration.getFactoryPid();
        final String identifier = factory == null ? configuration.getPid() : factory;
        final Map<String, AttributeDefinition> described = new HashMap<>();
        for (final Bundle bundle : installed.get()) {
            final MetaTypeInformation information = metatypes.getMetaTypeInformation(bundle);
            if (information == null || !describes(factory == null ? information.getPids()
                    : information.getFactoryPids(), identifier)) {
                continue;
            }
            final ObjectClassDefinition definition =
                    information.getObjectClassDefinition(identifier, null);
            final AttributeDefinition[] attributes = definition == null ? null
                    : definition.getAttributeDefinitions(ObjectClassDefinition.ALL);
            if (attributes != null) {
                Arrays.stream(attributes).forEach(attribute ->
                        described.putIfAbsent(attribute.getID(), attribute));
            }
        }
        return described;
    }

    private static boolean describes(String[] identifiers, String identifier) {
        return identifiers != null && Arrays.asList(identifiers).contains(identifier);
    }

    /**
     * One configuration's properties, in the order the configuration admin gives them, held in a
     * map this adapter may change without changing the configuration.
     */
    private static Map<String, Object> propertiesOf(Configuration configuration) {
        return Optional.ofNullable(configuration.getProperties())
                .<Map<String, Object>>map(held -> new LinkedHashMap<>(FrameworkUtil.asMap(held)))
                .orElseGet(LinkedHashMap::new);
    }

    private static Origin originOf(Configuration configuration) {
        return configuration.getFactoryPid() == null ? Origin.SINGLETON : Origin.FACTORY_INSTANCE;
    }

    /**
     * One identifier as an LDAP filter reads it literally.
     *
     * @param identifier what the caller wrote
     * @return the same text with every character a filter would read as syntax escaped
     */
    static String escaped(String identifier) {
        final StringBuilder escaped = new StringBuilder(identifier.length());
        for (final char character : identifier.toCharArray()) {
            if (character == '\\' || character == '*' || character == '(' || character == ')') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /** An identifier the configuration admin holds more than once. */
    private static final class Ambiguous extends Exception {

        private static final long serialVersionUID = 1L;

        /** The identifier held more than once. */
        private final String identifier;

        Ambiguous(String identifier) {
            super(identifier, null, false, false);
            this.identifier = identifier;
        }

        Failed failed() {
            return new Failed(LOOKUP_AMBIGUOUS, identifier + " names more than one configuration");
        }
    }
}
