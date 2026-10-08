// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;

/** Typed storage, cardinality and conversion refusals against the actual embedded repository. */
@ExtendWith(SlingContextExtension.class)
final class StoredPropertiesTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every scalar kind and both cardinalities keep their JCR types after a commit")
    void everyDeclaredKindKeepsItsRepositoryType() throws RepositoryException {
        final Resource target = sling.create().resource("/content/synthetic-typed-properties");
        final Map<String, PropertyValue> requested = new LinkedHashMap<>();
        for (final Sample sample : samples()) {
            requested.put(sample.kind().spelling(), new PropertyValue.Single(sample.scalar()));
            requested.put(sample.kind().spelling() + "_multiple",
                    new PropertyValue.Multiple(List.of(sample.scalar())));
        }
        final StoredProperties.Held prepared = held(requested, resolver());
        assertTrue(prepared.writeTo(target).isEmpty());
        session().save();
        final Node node = session().getNode(target.getPath());
        for (final Sample sample : samples()) {
            final javax.jcr.Property single = node.getProperty(sample.kind().spelling());
            final javax.jcr.Property multiple = node.getProperty(sample.kind().spelling() + "_multiple");
            assertEquals(sample.repositoryType(), single.getType());
            assertFalse(single.isMultiple());
            assertEquals(sample.repositoryType(), multiple.getType());
            assertTrue(multiple.isMultiple());
            assertEquals(1, multiple.getValues().length);
        }
        assertTrue(node.getProperty("boolean").getBoolean());
        assertEquals(Long.MIN_VALUE, node.getProperty("integer").getLong());
        assertEquals(new BigDecimal("12345678901234567890.1200"),
                node.getProperty("decimal").getDecimal());
        assertEquals("2026-01-01T00:00:00.123Z",
                node.getProperty("date_time").getDate().toInstant().toString());
        assertEquals("/content/synthetic-typed-properties", node.getProperty("repository_path").getString());
    }

    @Test
    @DisplayName("creation writes preserve booleans, integer bounds and path arrays")
    void creationMapsKeepTypedValues() throws org.apache.sling.api.resource.PersistenceException,
            RepositoryException {
        final Resource parent = sling.create().resource("/content/synthetic-typed-parent");
        final StoredProperties.Held properties = held(Map.of(
                "synthetic_boolean", value(ScalarKind.BOOLEAN, "false"),
                "synthetic_integers", new PropertyValue.Multiple(List.of(
                        scalar(ScalarKind.INTEGER, String.valueOf(Long.MIN_VALUE)),
                        scalar(ScalarKind.INTEGER, String.valueOf(Long.MAX_VALUE)))),
                "synthetic_paths", new PropertyValue.Multiple(List.of(
                        scalar(ScalarKind.REPOSITORY_PATH, parent.getPath())))), resolver());
        final Resource created = resolver().create(parent, "synthetic-child",
                Map.of("jcr:primaryType", "nt:unstructured"));
        assertTrue(properties.writeTo(created).isEmpty());
        resolver().commit();
        final Node node = session().getNode(created.getPath());
        assertEquals(PropertyType.BOOLEAN, node.getProperty("synthetic_boolean").getType());
        assertFalse(node.getProperty("synthetic_boolean").getBoolean());
        assertEquals(PropertyType.LONG, node.getProperty("synthetic_integers").getType());
        assertEquals(Long.MAX_VALUE, node.getProperty("synthetic_integers").getValues()[1].getLong());
        assertEquals(PropertyType.PATH, node.getProperty("synthetic_paths").getType());
        assertTrue(node.getProperty("synthetic_paths").isMultiple());
    }

    @Test
    @DisplayName("updates replace existing kinds and change cardinality in both directions")
    void existingPropertiesKeepTheNewRequestedKindAndCardinality() throws RepositoryException {
        final Resource target = sling.create().resource("/content/synthetic-existing-property",
                "synthetic_value", "synthetic-old-text");
        session().save();
        for (final Sample sample : samples()) {
            for (final PropertyValue value : List.of(new PropertyValue.Single(sample.scalar()),
                    new PropertyValue.Multiple(List.of(sample.scalar())),
                    new PropertyValue.Single(sample.scalar()))) {
                assertTrue(held(Map.of("synthetic_value", value), resolver()).writeTo(target).isEmpty());
                session().save();
                final javax.jcr.Property stored = session().getNode(target.getPath())
                        .getProperty("synthetic_value");
                assertEquals(sample.repositoryType(), stored.getType());
                assertEquals(value instanceof PropertyValue.Multiple, stored.isMultiple());
                if (stored.isMultiple()) {
                    assertEquals(1, stored.getValues().length);
                }
            }
        }
    }

    @Test
    @DisplayName("invalid conversions and mixed kinds are refused before a property map is served")
    void invalidOrMixedValuesProduceNoWritableMap() {
        for (final PropertyValue invalid : List.of(value(ScalarKind.BOOLEAN, "synthetic-invalid"),
                value(ScalarKind.INTEGER, "9223372036854775808"),
                value(ScalarKind.DECIMAL, "synthetic-invalid"),
                value(ScalarKind.DATE_TIME, "synthetic-invalid"),
                value(ScalarKind.DATE_TIME, "2026-01-01T00:00:00.123456Z"),
                value(ScalarKind.DATE_TIME, "2026-01-01T00:00:00.000Z"),
                new PropertyValue.Multiple(List.of()),
                new PropertyValue.Multiple(List.of(scalar(ScalarKind.STRING, "synthetic"),
                        scalar(ScalarKind.BOOLEAN, "true"))))) {
            final var properties = new LinkedHashMap<String, PropertyValue>();
            properties.put("synthetic_valid", value(ScalarKind.STRING, "synthetic"));
            properties.put("synthetic_invalid", invalid);
            assertInstanceOf(StoredProperties.Refused.class,
                    StoredProperties.of(change(properties), resolver()));
        }
        assertInstanceOf(StoredProperties.Held.class,
                StoredProperties.of(PropertyChange.NOTHING, resolver()));
    }

    @Test
    @DisplayName("a provider without a JCR session cannot turn a requested path into text")
    void aPathWithoutItsRepositoryFactoryIsRefused() {
        try (ResourceResolver withoutSession = new SlingContext(
                ResourceResolverType.RESOURCERESOLVER_MOCK).resourceResolver()) {
            assertInstanceOf(StoredProperties.Refused.class,
                    StoredProperties.of(change(Map.of("synthetic_path",
                            value(ScalarKind.REPOSITORY_PATH, "/content/synthetic"))), withoutSession));
        }
    }

    @Test
    @DisplayName("a mutable provider without JCR keeps native kinds and refuses a typed JCR path")
    void aNonJcrProviderDoesNotLoseKinds() {
        final StoredProperties.Held path = held(Map.of("synthetic_path",
                value(ScalarKind.REPOSITORY_PATH, "/content/synthetic")), resolver());
        try (ResourceResolver withoutSession = new SlingContext(
                ResourceResolverType.RESOURCERESOLVER_MOCK).resourceResolver()) {
            final Resource target;
            try {
                target = withoutSession.create(java.util.Objects.requireNonNull(
                        withoutSession.getResource("/")), "synthetic-target",
                        Map.of("jcr:primaryType", "nt:unstructured"));
            } catch (final org.apache.sling.api.resource.PersistenceException refused) {
                throw new IllegalStateException(refused);
            }
            assertTrue(path.writeTo(target).isPresent());
            final StoredProperties.Held nativeValues = held(Map.of("synthetic_flag",
                    value(ScalarKind.BOOLEAN, "true")), withoutSession);
            assertTrue(nativeValues.writeTo(target).isEmpty());
            assertEquals(Boolean.TRUE, target.getValueMap().get("synthetic_flag"));
        }
    }

    @Test
    @DisplayName("unmodifiable resources, protected properties and undeclared Java types refuse")
    void writeRefusalsCommitNothing() throws RepositoryException {
        final Resource target = sling.create().resource("/content/synthetic-refusal-target");
        session().save();
        assertTrue(held(Map.of("synthetic_flag", value(ScalarKind.BOOLEAN, "true")), resolver())
                .writeTo(new SyntheticResource(resolver(), "/content/synthetic-virtual", "synthetic"))
                .isPresent());
        assertTrue(new StoredProperties.Held(Map.of("jcr:primaryType", Boolean.TRUE))
                .writeTo(target).isPresent());
        assertTrue(new StoredProperties.Held(Map.of("synthetic_invalid", new Object()))
                .writeTo(target).isPresent());
        assertFalse(session().hasPendingChanges());
        assertFalse(session().getNode(target.getPath()).hasProperty("synthetic_invalid"));
    }

    private static List<Sample> samples() {
        return List.of(new Sample(ScalarKind.STRING, "synthetic", PropertyType.STRING),
                new Sample(ScalarKind.BOOLEAN, "true", PropertyType.BOOLEAN),
                new Sample(ScalarKind.INTEGER, String.valueOf(Long.MIN_VALUE), PropertyType.LONG),
                new Sample(ScalarKind.DECIMAL, "12345678901234567890.1200", PropertyType.DECIMAL),
                new Sample(ScalarKind.DATE_TIME, "2026-01-01T00:00:00.123Z", PropertyType.DATE),
                new Sample(ScalarKind.REPOSITORY_PATH, "/content/synthetic-typed-properties",
                        PropertyType.PATH));
    }

    private static PropertyScalar scalar(ScalarKind kind, String text) {
        return new PropertyScalar(kind, text);
    }

    private static PropertyValue value(ScalarKind kind, String text) {
        return new PropertyValue.Single(scalar(kind, text));
    }

    private static PropertyChange change(Map<String, PropertyValue> properties) {
        return new PropertyChange(new LinkedHashMap<>(properties), new LinkedHashSet<>());
    }

    private static StoredProperties.Held held(Map<String, PropertyValue> properties,
            ResourceResolver resolver) {
        return assertInstanceOf(StoredProperties.Held.class,
                StoredProperties.of(change(properties), resolver));
    }

    private ResourceResolver resolver() {
        return sling.resourceResolver();
    }

    private Session session() {
        return java.util.Objects.requireNonNull(resolver().adaptTo(Session.class));
    }

    private record Sample(ScalarKind kind, String text, int repositoryType) {
        private PropertyScalar scalar() {
            return new PropertyScalar(kind, text);
        }
    }
}
