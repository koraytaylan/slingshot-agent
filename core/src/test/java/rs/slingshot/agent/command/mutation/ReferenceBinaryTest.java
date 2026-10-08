// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

/** Exact string-reference evidence beside a binary whose value must never be requested. */
final class ReferenceBinaryTest {

    private static final String TARGET = "/content/synthetic-target";
    private static final String MOVED = "/content/synthetic-moved";
    private static final String UNRELATED = "/content/synthetic-unrelated";

    @Test
    void referenceDiscoveryNeverReadsBinaryOrEnumeratesConvertedValues() {
        final Map<String, Object> stored = stored();
        final Resource source = source(stored);
        final Resource root = resource("/content", new ValueMapDecorator(Map.of()),
                List.of(source), null);
        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 2);

            assertTrue(found.complete());
            assertEquals(List.of(source), found.found());
        }
    }

    @Test
    void referenceRewritingNeverReadsBinaryOrEnumeratesConvertedEntries()
            throws org.apache.sling.api.resource.PersistenceException {
        final Map<String, Object> stored = stored();

        assertEquals(3, RepositoryReach.repointed(List.of(source(stored)), TARGET, MOVED));
        assertEquals(MOVED, stored.get("link"));
        assertEquals(List.of(MOVED, UNRELATED, MOVED), List.of((String[]) stored.get("links")));
    }

    @Test
    void selectionSkipsEveryNonTextKindBeforeAskingForItsNameOrValue() {
        final List<Property> properties = List.of(PropertyType.BINARY, PropertyType.BOOLEAN,
                        PropertyType.LONG, PropertyType.DOUBLE, PropertyType.DECIMAL, PropertyType.DATE)
                .stream().map(kind -> proxy(Property.class, method ->
                        "getType".equals(method) ? kind : refused(method))).toList();
        final ReferenceProperties.Selection selected = ReferenceProperties.select(
                source(properties, Map.of()));

        assertTrue(selected.complete());
        assertTrue(selected.names().isEmpty());
        assertEquals(java.util.Optional.of(false), ReferenceProperties.mentions(
                source(properties, Map.of()), TARGET));
    }

    @Test
    void selectionPreservesAllStringRepresentationsAndExactMatchSemantics() {
        final List<Integer> kinds = List.of(PropertyType.STRING, PropertyType.NAME, PropertyType.PATH,
                PropertyType.URI, PropertyType.REFERENCE, PropertyType.WEAKREFERENCE,
                PropertyType.UNDEFINED);
        final List<Property> properties = kinds.stream().map(kind -> proxy(Property.class,
                method -> switch (method) {
                    case "getType" -> kind;
                    case "getName" -> "link";
                    default -> refused(method);
                })).toList();

        assertEquals(kinds.size(), ReferenceProperties.select(source(properties,
                Map.of("link", TARGET))).names().size());
        assertEquals(java.util.Optional.of(true), ReferenceProperties.mentions(
                source(properties, Map.of("link", TARGET)), TARGET));
        assertEquals(java.util.Optional.of(false), ReferenceProperties.mentions(
                source(properties, Map.of("link", TARGET + "/child")), TARGET));
        assertEquals(java.util.Optional.of(false), ReferenceProperties.mentions(
                source(properties, Map.of("link", new String[]{UNRELATED})), TARGET));
    }

    @Test
    void unreadablePropertyTypeOrNameNeverProducesCompleteEvidence() {
        for (final String failed : List.of("getType", "getName")) {
            final Resource source = source(List.of(property("link", false), unreadable(failed)),
                    Map.of("link", TARGET));

            assertFalse(ReferenceProperties.select(source).complete());
            assertTrue(ReferenceProperties.mentions(source, TARGET).isEmpty());
        }
    }

    @Test
    void unreadablePropertyEnumerationNeverProducesCompleteEvidence() {
        final Node node = Node.class.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]{Node.class},
                (held, method, arguments) -> {
                    throw new RepositoryException("synthetic refusal");
                }));
        final Resource source = resource("/content/synthetic-reference",
                new ValueMapDecorator(Map.of()), List.of(), node);

        assertFalse(ReferenceProperties.select(source).complete());
        assertTrue(ReferenceProperties.mentions(source, TARGET).isEmpty());
    }

    @Test
    void unreadableMetadataRefusesDiscoveryAndRewritingBeforeAnyAssignment() {
        final Map<String, Object> stored = stored();
        final Resource source = source(List.of(property("link", false), unreadable("getType")),
                stored);
        final Resource root = resource("/content", new ValueMapDecorator(Map.of()),
                List.of(source), null);
        try (ResourceResolver resolver = resolver(root)) {
            assertFalse(RepositoryReach.references(resolver, TARGET, 2).complete());
        }
        assertThrows(org.apache.sling.api.resource.PersistenceException.class,
                () -> RepositoryReach.repointed(List.of(source), TARGET, MOVED));
        assertEquals(TARGET, stored.get("link"));
        assertEquals(List.of(TARGET, UNRELATED, TARGET), List.of((String[]) stored.get("links")));
    }

    private static Map<String, Object> stored() {
        return new HashMap<>(Map.of("link", TARGET, "links", new String[]{TARGET, UNRELATED, TARGET}));
    }

    private static ResourceResolver resolver(Resource root) {
        return proxy(ResourceResolver.class, method -> switch (method) {
            case "getResource" -> root;
            case "close" -> null;
            default -> refused(method);
        });
    }

    private static Resource source(Map<String, Object> stored) {
        final Property binary = proxy(Property.class, method -> switch (method) {
            case "getType" -> PropertyType.BINARY;
            case "getName" -> "jcr:data";
            default -> throw new AssertionError("binary value was requested: " + method);
        });
        final List<Property> properties = List.of(binary, property("link", false),
                property("links", true));
        return source(properties, stored);
    }

    private static Resource source(List<Property> properties, Map<String, Object> stored) {
        final Node node = proxy(Node.class, method ->
                "getProperties".equals(method) ? iterator(properties) : refused(method));
        final ModifiableValueMap values = ModifiableValueMap.class.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]{ModifiableValueMap.class},
                (held, method, arguments) -> switch (method.getName()) {
                    case "keySet" -> Set.of("jcr:data", "link", "links");
                    case "get" -> "jcr:data".equals(arguments[0])
                            ? refused("binary conversion") : stored.get(arguments[0]);
                    case "put" -> stored.put(String.valueOf(arguments[0]), arguments[1]);
                    default -> refused(method.getName());
                }));
        return resource("/content/synthetic-reference", values, List.of(), node);
    }

    private static Property unreadable(String failed) {
        return Property.class.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{Property.class}, (held, method, arguments) -> {
                    if (failed.equals(method.getName())) {
                        throw new RepositoryException("synthetic refusal");
                    }
                    return "getType".equals(method.getName()) ? PropertyType.STRING : "unreadable";
                }));
    }

    private static Property property(String name, boolean multiple) {
        final Value value = proxy(Value.class, method ->
                "getString".equals(method) ? TARGET : refused(method));
        return proxy(Property.class, method -> switch (method) {
            case "getType" -> PropertyType.STRING;
            case "getName" -> name;
            case "isMultiple" -> multiple;
            case "getValue" -> value;
            case "getValues" -> new Value[]{value, value};
            default -> refused(method);
        });
    }

    private static PropertyIterator iterator(List<Property> properties) {
        final Iterator<Property> iterator = properties.iterator();
        return proxy(PropertyIterator.class, method -> switch (method) {
            case "hasNext" -> iterator.hasNext();
            case "next", "nextProperty" -> iterator.next();
            default -> refused(method);
        });
    }

    private static Resource resource(String path, org.apache.sling.api.resource.ValueMap values,
                                     List<Resource> children, Node node) {
        return Resource.class.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{Resource.class}, (held, method, arguments) -> switch (method.getName()) {
                    case "getPath", "toString" -> path;
                    case "getValueMap" -> values;
                    case "listChildren" -> children.iterator();
                    case "adaptTo" -> Node.class.equals(arguments[0]) ? node : values;
                    default -> refused(method.getName());
                }));
    }

    private static Object refused(String method) {
        throw new AssertionError("unnecessary converted property access: " + method);
    }

    private static <Answer> Answer proxy(Class<Answer> type, Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type}, (held, method, arguments) -> answer.apply(method.getName())));
    }
}
