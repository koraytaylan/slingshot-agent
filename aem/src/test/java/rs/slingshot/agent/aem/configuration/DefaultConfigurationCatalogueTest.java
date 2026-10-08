// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
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
 * The configuration commands' adapter, driven over a configuration admin the suite scripts.
 *
 * <p>What is proved is the translation, and above all the one thing the adapter must never get
 * wrong: a property the Meta Type Service calls a password, or does not describe at all, is never
 * converted, so its value never reaches an answer.</p>
 */
final class DefaultConfigurationCatalogueTest {

    @Test
    @DisplayName("a search lists by prefix in identifier order, escaping the filter's own syntax")
    void asearchListsByPrefix() {
        final Platform platform = new Platform();
        final List<ConfigurationCatalogue.Entry> found = assertInstanceOf(
                ConfigurationCatalogue.Listed.class, platform.catalogue().find("com.acme",
                        LIMIT)).entries();
        assertEquals(List.of(
                new ConfigurationCatalogue.Entry("com.acme.Factory~one", "com.acme.Factory", 2,
                        ConfigurationCatalogue.Binding.UNBOUND),
                new ConfigurationCatalogue.Entry("com.acme.Service", ConfigurationCatalogue
                        .NOT_FROM_A_FACTORY, 5, ConfigurationCatalogue.Binding
                        .BOUND_TO_A_BUNDLE_LOCATION)), found);
        assertEquals("(service.pid=com.acme*)", platform.filters.getFirst());
        assertEquals("configuration_lookup_budget_exceeded", assertInstanceOf(
                ConfigurationCatalogue.Failed.class, platform.catalogue().find("", 1)).category());
        assertEquals("a\\*b\\(c\\)d\\\\", DefaultConfigurationCatalogue.escaped("a*b(c)d\\"));
    }

    @Test
    @DisplayName("only a property described as not a password has its value read")
    void onlyADescribedPropertyIsRead() {
        final Platform platform = new Platform();
        final ConfigurationCatalogue.Inspected inspected = assertInstanceOf(
                ConfigurationCatalogue.Inspected.class, platform.catalogue().inspect(
                        "com.acme.Service"));
        assertEquals(ConfigurationCatalogue.Presence.PRESENT, inspected.present());
        final Map<String, ConfigurationCatalogue.Property> named = new LinkedHashMap<>();
        inspected.properties().forEach(property -> named.put(property.name(), property));
        assertEquals(ValueDisclosure.Evidence.PASSWORD, named.get("secret").evidence());
        assertEquals(List.of(), named.get("secret").value().values(), "a password was read");
        assertEquals(ValueDisclosure.Evidence.UNAVAILABLE, named.get("stray").evidence());
        assertEquals(List.of(), named.get("stray").value().values(),
                "an undescribed property was read");
        assertEquals(new ConfigurationValue("integer", ConfigurationValue.Cardinality.SCALAR,
                List.of("8080")), named.get("port").value());
        assertEquals(ValueDisclosure.Evidence.UNAVAILABLE, named.get("service.pid").evidence());
        assertEquals(ValueDisclosure.Evidence.NON_PASSWORD, named.get("api.token").evidence());
        assertEquals(List.of(), named.get("api.token").value().values(),
                "a property named like a secret was read");
        assertEquals(ConfigurationCatalogue.Presence.ABSENT, assertInstanceOf(
                ConfigurationCatalogue.Inspected.class, platform.catalogue().inspect("none"))
                .present());
        final ConfigurationCatalogue.Inspected factory = assertInstanceOf(
                ConfigurationCatalogue.Inspected.class, platform.catalogue().inspect(
                        "com.acme.Factory~one"));
        assertEquals(ValueDisclosure.Evidence.NON_PASSWORD, factory.properties().stream()
                .filter(property -> "name".equals(property.name())).findFirst().orElseThrow()
                .evidence(), "a factory instance is described by its factory");
    }

    @Test
    @DisplayName("every type and cardinality reads in the client's words")
    void everyValueReadsInTheClientsWords() {
        assertEquals(new ConfigurationValue("integer", ConfigurationValue.Cardinality.SCALAR,
                List.of("3")), PropertyValues.read(3).orElseThrow());
        assertEquals(new ConfigurationValue("integer",
                ConfigurationValue.Cardinality.PRIMITIVE_ARRAY, List.of("1", "2")),
                PropertyValues.read(new int[] {1, 2}).orElseThrow());
        assertEquals(new ConfigurationValue("string", ConfigurationValue.Cardinality.SCALAR_ARRAY,
                List.of("a", "b")), PropertyValues.read(new String[] {"a", "b"}).orElseThrow());
        assertEquals(new ConfigurationValue("double", ConfigurationValue.Cardinality.COLLECTION,
                List.of("3ff8000000000000")), PropertyValues.read(List.of(1.5d)).orElseThrow());
        assertEquals(new ConfigurationValue("string", ConfigurationValue.Cardinality.COLLECTION,
                List.of()), PropertyValues.read(List.of()).orElseThrow());
        assertEquals(new ConfigurationValue("float", ConfigurationValue.Cardinality.SCALAR,
                List.of("40b00000")), PropertyValues.read(5.5f).orElseThrow());
        assertEquals(new ConfigurationValue("double", ConfigurationValue.Cardinality.SCALAR,
                List.of("3ff0000000000000")), PropertyValues.read(1.0d).orElseThrow());
        assertEquals(Optional.empty(), PropertyValues.read(new Object()));
        assertEquals(Optional.empty(), PropertyValues.read(new Object[] {}));
        assertEquals(Optional.empty(), PropertyValues.read(List.of(new Object())));
    }

    @Test
    @DisplayName("an identifier the configuration admin holds twice is ambiguous")
    void anidentifierHeldTwiceIsAmbiguous() {
        final Platform platform = new Platform();
        assertEquals("configuration_lookup_ambiguous", assertInstanceOf(
                ConfigurationCatalogue.Failed.class, platform.catalogue().inspect(TWICE))
                .category());
    }

    /** An identifier the scripted configuration admin holds twice. */
    private static final String TWICE = "org.twice";

    /** How many configurations one search may examine here. */
    private static final long LIMIT = 10;

    /** A configuration admin of two configurations, and one bundle that describes both. */
    private static final class Platform {

        private final List<String> filters = new ArrayList<>();
        private final Map<String, Map<String, Object>> held = new LinkedHashMap<>();

        Platform() {
            final Map<String, Object> service = new LinkedHashMap<>();
            service.put("service.pid", "com.acme.Service");
            service.put("port", 8080);
            service.put("secret", "hunter2");
            service.put("stray", "sk-live");
            service.put("api.token", "tok-live");
            held.put("com.acme.Service", service);
            final Map<String, Object> factory = new LinkedHashMap<>();
            factory.put("service.pid", "com.acme.Factory~one");
            factory.put("name", "one");
            held.put("com.acme.Factory~one", factory);
        }

        ConfigurationCatalogue catalogue() {
            final Bundle bundle = proxy(Bundle.class, (method, arguments) -> {
                throw new UnsupportedOperationException(method);
            });
            final BundleContext context = proxy(BundleContext.class, (method, arguments) ->
                    new Bundle[] {bundle, bundle});
            final MetaTypeService metatypes = proxy(MetaTypeService.class,
                    (method, arguments) -> information());
            final ConfigurationAdmin admin = proxy(ConfigurationAdmin.class, (method, arguments) ->
                    listed((String) arguments[0]));
            return new ConfigurationAdminCatalogues(context, admin, metatypes).open();
        }

        private Configuration[] listed(String filter) {
            filters.add(filter);
            if (("(service.pid=" + TWICE + ")").equals(filter)) {
                return new Configuration[] {configuration(TWICE), configuration(TWICE)};
            }
            final String wanted = filter.substring("(service.pid=".length(), filter.length() - 1)
                    .replace("\\", "");
            final List<Configuration> found = held.keySet().stream()
                    .filter(pid -> wanted.endsWith("*") ? pid.startsWith(wanted.substring(0,
                            wanted.length() - 1)) : pid.equals(wanted))
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(this::configuration)
                    .toList();
            return found.toArray(Configuration[]::new);
        }

        private Configuration configuration(String pid) {
            final boolean factory = pid.contains("~");
            return proxy(Configuration.class, (method, arguments) -> switch (method) {
                case "getPid" -> pid;
                case "getFactoryPid" -> factory ? pid.substring(0, pid.indexOf('~')) : null;
                case "getBundleLocation" -> factory ? null : "launchpad:acme";
                case "getProperties" -> FrameworkUtil.asDictionary(new LinkedHashMap<>(
                        held.get(pid)));
                default -> throw new IOException(method);
            });
        }

        private static MetaTypeInformation information() {
            final Map<String, ObjectClassDefinition> definitions = Map.of(
                    "com.acme.Service", definition(attribute("port", AttributeDefinition.INTEGER),
                            attribute("secret", AttributeDefinition.PASSWORD),
                            attribute("api.token", AttributeDefinition.STRING)),
                    "com.acme.Factory", definition(attribute("name",
                            AttributeDefinition.STRING)));
            return proxy(MetaTypeInformation.class, (method, arguments) -> switch (method) {
                case "getPids" -> new String[] {"com.acme.Service"};
                case "getFactoryPids" -> new String[] {"com.acme.Factory"};
                case "getObjectClassDefinition" -> definitions.get((String) arguments[0]);
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private static ObjectClassDefinition definition(AttributeDefinition... attributes) {
            return proxy(ObjectClassDefinition.class, (method, arguments) -> attributes);
        }

        private static AttributeDefinition attribute(String identifier, int type) {
            return proxy(AttributeDefinition.class, (method, arguments) -> switch (method) {
                case "getID" -> identifier;
                case "getType" -> type;
                default -> throw new UnsupportedOperationException(method);
            });
        }
    }

    /** Answers one scripted method by name. */
    @FunctionalInterface
    private interface Answering {

        /**
         * The answer to one call.
         *
         * @param method the method's name
         * @param arguments what it was called with
         * @return the answer
         * @throws IOException whatever the scripted method throws
         */
        Object answer(String method, Object... arguments) throws IOException;
    }

    private static <T> T proxy(Class<T> type, Answering answering) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type}, (proxy, method, arguments) ->
                        answering.answer(method.getName(), arguments)));
    }
}
