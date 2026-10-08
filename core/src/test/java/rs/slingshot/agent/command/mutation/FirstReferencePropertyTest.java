// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Deletion stops at decisive property evidence while complete move discovery stays conservative. */
final class FirstReferencePropertyTest {

    private static final String TARGET = "/content/synthetic-target";
    private static final String OTHER = "/content/synthetic-unrelated";

    private enum TextKind {
        STRING(PropertyType.STRING), NAME(PropertyType.NAME), PATH(PropertyType.PATH),
        URI(PropertyType.URI), REFERENCE(PropertyType.REFERENCE),
        WEAK_REFERENCE(PropertyType.WEAKREFERENCE), UNDEFINED(PropertyType.UNDEFINED);

        private final int code;

        TextKind(int code) {
            this.code = code;
        }
    }

    private enum NonTextKind {
        BINARY(PropertyType.BINARY), BOOLEAN(PropertyType.BOOLEAN), LONG(PropertyType.LONG),
        DOUBLE(PropertyType.DOUBLE), DECIMAL(PropertyType.DECIMAL), DATE(PropertyType.DATE);

        private final int code;

        NonTextKind(int code) {
            this.code = code;
        }
    }

    @ParameterizedTest
    @EnumSource(TextKind.class)
    void scalarMatchDoesNotInspectLaterUnreadableMetadata(TextKind kind) {
        decisiveMatch(kind, TARGET);
    }

    @ParameterizedTest
    @EnumSource(TextKind.class)
    void multipleMatchDoesNotInspectLaterUnreadableMetadata(TextKind kind) {
        decisiveMatch(kind, new String[]{OTHER, TARGET, OTHER});
    }

    private static void decisiveMatch(TextKind kind, Object stored) {
        final AtomicInteger advances = new AtomicInteger();
        final Resource source = source(List.of(text(kind, "link"), unreadable()),
                new ValueMapDecorator(Map.of("link", stored)), advances);
        try (ResourceResolver resolver = resolver(source)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 1));
            assertEquals(1, advances.get());
            advances.set(0);
            assertFalse(RepositoryReach.references(resolver, TARGET, 1).complete());
            assertEquals(2, advances.get());
        }
    }

    @ParameterizedTest
    @EnumSource(NonTextKind.class)
    void nonTextMetadataNeverRequestsANameOrConvertedValue(NonTextKind kind) {
        final AtomicInteger advances = new AtomicInteger();
        final Property nonText = proxy(Property.class, method ->
                "getType".equals(method) ? kind.code : refused(method));
        final ValueMap values = proxy(ValueMap.class, method -> refused(method));
        final Resource source = source(List.of(nonText), values, advances);
        try (ResourceResolver resolver = resolver(source)) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 1));
            assertEquals(1, advances.get());
            assertTrue(RepositoryReach.references(resolver, TARGET, 1).complete());
            assertEquals(2, advances.get());
        }
    }

    @Test
    void absentReferenceInspectsEveryTextPropertyAndUsesExactMatching() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource source = source(List.of(text(TextKind.STRING, "prefix"), text(TextKind.PATH, "links")),
                new ValueMapDecorator(Map.of("prefix", TARGET + "/child", "links", new String[]{OTHER})),
                advances);
        try (ResourceResolver resolver = resolver(source)) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 1));
            assertEquals(2, advances.get());
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 1);
            assertTrue(found.complete());
            assertTrue(found.found().isEmpty());
            assertEquals(4, advances.get());
        }
    }

    @Test
    void unreadableMetadataBeforeAReferenceRefusesDeletionWithoutReadingFurther() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource source = source(List.of(unreadable(), text(TextKind.STRING, "link")),
                new ValueMapDecorator(Map.of("link", TARGET)), advances);
        try (ResourceResolver resolver = resolver(source)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 1));
            assertEquals(1, advances.get());
            assertFalse(RepositoryReach.references(resolver, TARGET, 1).complete());
            assertEquals(3, advances.get());
        }
    }

    @Test
    void unreadableEnumerationNeverAllowsDeletion() {
        final Node node = Node.class.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]{Node.class},
                (held, method, arguments) -> {
                    throw new RepositoryException("synthetic refusal");
                }));
        final Resource source = source(node, new ValueMapDecorator(Map.of()));
        try (ResourceResolver resolver = resolver(source)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 1));
            assertFalse(RepositoryReach.references(resolver, TARGET, 1).complete());
        }
    }

    @Test
    void budgetExhaustionWithoutReferenceRemainsADeletionRefusal() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource source = source(List.of(), new ValueMapDecorator(Map.of()), advances);
        try (ResourceResolver resolver = resolver(source)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 0));
            assertEquals(0, advances.get());
            assertFalse(RepositoryReach.references(resolver, TARGET, 0).complete());
        }
    }

    private static Property text(TextKind kind, String name) {
        return proxy(Property.class, method -> switch (method) {
            case "getType" -> kind.code;
            case "getName" -> name;
            default -> refused(method);
        });
    }

    private static Property unreadable() {
        return Property.class.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]{Property.class},
                (held, method, arguments) -> {
                    throw new RepositoryException("synthetic refusal");
                }));
    }

    private static Resource source(List<Property> properties, ValueMap values, AtomicInteger advances) {
        final Node node = proxy(Node.class, method -> {
            if (!"getProperties".equals(method)) {
                return refused(method);
            }
            final Iterator<Property> iterator = properties.iterator();
            return proxy(PropertyIterator.class, operation -> switch (operation) {
                case "hasNext" -> iterator.hasNext();
                case "nextProperty", "next" -> {
                    advances.incrementAndGet();
                    yield iterator.next();
                }
                default -> refused(operation);
            });
        });
        return source(node, values);
    }

    private static Resource source(Node node, ValueMap values) {
        return proxy(Resource.class, method -> switch (method) {
            case "getPath" -> "/content/synthetic-reference";
            case "getValueMap" -> values;
            case "adaptTo" -> node;
            case "listChildren" -> List.of().iterator();
            default -> refused(method);
        });
    }

    private static ResourceResolver resolver(Resource source) {
        return proxy(ResourceResolver.class, method -> switch (method) {
            case "getResource" -> source;
            case "close" -> null;
            default -> refused(method);
        });
    }

    private static <Type> Type proxy(Class<Type> type, Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type}, (held, method, arguments) -> answer.apply(method.getName())));
    }

    private static Object refused(String method) {
        throw new AssertionError("unexpected property or value access: " + method);
    }
}
