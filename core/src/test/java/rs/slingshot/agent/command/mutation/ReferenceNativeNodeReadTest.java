// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

/** Complete metadata selection shares one native adaptation per visit without retaining values. */
final class ReferenceNativeNodeReadTest {

    private static final String TARGET = "/content/synthetic-target";
    private static final String OTHER = "/content/synthetic-other";
    private static final int RESOURCES = 128;
    private static final List<String> NAMES = List.of("synthetic:first", "synthetic:second",
            "synthetic:third", "synthetic:fourth", "synthetic:fifth", "synthetic:_x0020_last");

    private record Counts(AtomicInteger adaptations, AtomicInteger metadata, AtomicInteger values) {

        Counts() {
            this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        }
    }

    @Test
    void absentReferencesAdaptOnceForEachResourceAndReadAllNativeValues() {
        final Counts counts = new Counts();
        IntStream.range(0, RESOURCES).forEach(index -> {
            final Node node = nativeNode(counts, OTHER, false, false);
            assertEquals(Optional.of(false), ReferenceProperties.mentions(resource(counts,
                    new AtomicReference<>(node), new ValueMapDecorator(Map.of()), false), TARGET));
        });
        assertEquals(RESOURCES * NAMES.size(), counts.metadata().get());
        assertEquals(RESOURCES * NAMES.size(), counts.values().get());
        assertEquals(RESOURCES, counts.adaptations().get());
    }

    @Test
    void exactMultipleMatchStillInspectsAllMetadataBeforeReadingOneValue() {
        final Counts counts = new Counts();
        final Node node = nativeNode(counts, new String[]{OTHER, TARGET, OTHER}, false, false);
        assertEquals(Optional.of(true), ReferenceProperties.mentions(resource(counts,
                new AtomicReference<>(node), new ValueMapDecorator(Map.of()), false), TARGET));
        assertEquals(NAMES.size(), counts.metadata().get());
        assertEquals(1, counts.values().get());
        assertEquals(1, counts.adaptations().get());
    }

    @Test
    void subsequentVisitsAdaptAgainAndReadTheCurrentNativeNode() {
        final Counts counts = new Counts();
        final AtomicReference<Node> current = new AtomicReference<>(nativeNode(counts, OTHER, false, false));
        final Resource resource = resource(counts, current, new ValueMapDecorator(Map.of()), false);
        assertEquals(Optional.of(false), ReferenceProperties.mentions(resource, TARGET));
        current.set(nativeNode(counts, TARGET, false, false));
        assertEquals(Optional.of(true), ReferenceProperties.mentions(resource, TARGET));
        assertEquals(2 * NAMES.size(), counts.metadata().get());
        assertEquals(NAMES.size() + 1, counts.values().get());
        assertEquals(2, counts.adaptations().get());
    }

    @Test
    void legacySelectionKeepsASecondNativeAdaptationWhenTheAdapterBecomesAvailable() {
        final Counts counts = new Counts();
        final Node node = nativeNode(counts, TARGET, false, false);
        final ValueMap legacy = new ValueMapDecorator(Map.of(NAMES.getFirst(), OTHER));
        assertEquals(Optional.of(true), ReferenceProperties.mentions(resource(counts,
                new AtomicReference<>(node), legacy, true), TARGET));
        assertEquals(0, counts.metadata().get());
        assertEquals(1, counts.values().get());
        assertEquals(2, counts.adaptations().get());
    }

    @Test
    void unreadableLaterMetadataRefusesBeforeAnyNativeValueRead() {
        final Counts counts = new Counts();
        final Node node = nativeNode(counts, TARGET, true, false);
        assertEquals(Optional.empty(), ReferenceProperties.mentions(resource(counts,
                new AtomicReference<>(node), new ValueMapDecorator(Map.of()), false), TARGET));
        assertEquals(NAMES.size(), counts.metadata().get());
        assertEquals(0, counts.values().get());
        assertEquals(1, counts.adaptations().get());
    }

    @Test
    void unreadableNativeValueStillRefusesWithoutReadingLaterProperties() {
        final Counts counts = new Counts();
        final Node node = nativeNode(counts, TARGET, false, true);
        assertEquals(Optional.empty(), ReferenceProperties.mentions(resource(counts,
                new AtomicReference<>(node), new ValueMapDecorator(Map.of()), false), TARGET));
        assertEquals(NAMES.size(), counts.metadata().get());
        assertEquals(1, counts.values().get());
        assertEquals(1, counts.adaptations().get());
    }

    private static Resource resource(Counts counts, AtomicReference<Node> current,
                                     ValueMap legacy, boolean initiallyAbsent) {
        return proxy(Resource.class, (held, method, arguments) -> switch (method.getName()) {
            case "adaptTo" -> {
                final int visit = counts.adaptations().incrementAndGet();
                yield initiallyAbsent && visit == 1 ? null : current.get();
            }
            case "getValueMap" -> legacy;
            default -> refused(method.getName());
        });
    }

    private static Node nativeNode(Counts counts, Object first, boolean unreadableMetadata,
                                   boolean unreadableValue) {
        final List<Property> properties = NAMES.stream().map(name -> property(counts, name,
                name.equals(NAMES.getFirst()) ? first : OTHER,
                unreadableMetadata && name.equals(NAMES.getLast()),
                unreadableValue && name.equals(NAMES.getFirst()))).toList();
        return proxy(Node.class, (held, method, arguments) -> switch (method.getName()) {
            case "getProperties" -> iterator(counts, properties);
            case "getProperty" -> properties.get(NAMES.indexOf(arguments[0]));
            default -> refused(method.getName());
        });
    }

    private static PropertyIterator iterator(Counts counts, List<Property> properties) {
        final Iterator<Property> iterator = properties.iterator();
        return proxy(PropertyIterator.class, (held, method, arguments) -> switch (method.getName()) {
            case "hasNext" -> iterator.hasNext();
            case "nextProperty", "next" -> {
                counts.metadata().incrementAndGet();
                yield iterator.next();
            }
            default -> refused(method.getName());
        });
    }

    private static Property property(Counts counts, String name, Object text,
                                      boolean unreadableMetadata, boolean unreadableValue) {
        return proxy(Property.class, (held, method, arguments) -> {
            if (unreadableMetadata && "getType".equals(method.getName())) {
                throw new RepositoryException("synthetic unreadable metadata");
            }
            if ("getValue".equals(method.getName()) || "getValues".equals(method.getName())) {
                counts.values().incrementAndGet();
                if (unreadableValue) {
                    throw new RepositoryException("synthetic unreadable value");
                }
            }
            return switch (method.getName()) {
                case "getType" -> PropertyType.PATH;
                case "getName" -> name;
                case "isMultiple" -> text instanceof String[];
                case "getValue" -> value((String) text);
                case "getValues" -> java.util.Arrays.stream((String[]) text)
                        .map(ReferenceNativeNodeReadTest::value).toArray(Value[]::new);
                default -> refused(method.getName());
            };
        });
    }

    private static Value value(String text) {
        return proxy(Value.class, (held, method, arguments) ->
                "getString".equals(method.getName()) ? text : refused(method.getName()));
    }

    private static <Type> Type proxy(Class<Type> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type}, handler));
    }

    private static Object refused(String method) {
        throw new AssertionError("unexpected synthetic native read: " + method);
    }
}
