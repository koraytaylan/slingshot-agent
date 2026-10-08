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

/** Complete reference discovery reuses selected native handles only within one visit, after all metadata. */
final class ReferencePropertyHandleReadTest {

    private static final String TARGET = "/content/synthetic-target";
    private static final String OTHER = "/content/synthetic-other";
    private static final int RESOURCES = 128;
    private static final List<String> NAMES = List.of("synthetic:first", "synthetic:second",
            "synthetic:third", "synthetic:fourth", "synthetic:fifth", "synthetic:_x0020_last");

    private record Counts(AtomicInteger adaptations, AtomicInteger metadata, AtomicInteger values,
                          AtomicInteger lookups) {

        Counts() {
            this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
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
        assertEquals(0, counts.lookups().get(), "selected native properties must not be looked up again");
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
        assertEquals(0, counts.lookups().get(), "selected native properties must not be looked up again");
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
        assertEquals(0, counts.lookups().get(), "selected native properties must not be looked up again");
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
        assertEquals(1, counts.lookups().get(), "legacy names still acquire their current native property");
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
        assertEquals(0, counts.lookups().get(), "selected native properties must not be looked up again");
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
        assertEquals(0, counts.lookups().get(), "selected native properties must not be looked up again");
    }

    @Test
    void ordinaryNamesStillReadTheirCurrentProviderValueWithoutNativeLookup() {
        final Counts counts = new Counts();
        final AtomicReference<String> current = new AtomicReference<>(OTHER);
        final AtomicInteger providerReads = new AtomicInteger();
        final Property property = proxy(Property.class, (held, method, arguments) ->
                switch (method.getName()) {
                    case "getType" -> PropertyType.STRING;
                    case "getName" -> "synthetic_link";
                    default -> refused(method.getName());
                });
        final Node node = proxy(Node.class, (held, method, arguments) ->
                "getProperties".equals(method.getName())
                        ? iterator(counts, List.of(property)) : refused(method.getName()));
        final ValueMap values = proxy(ValueMap.class, (held, method, arguments) -> {
            if (!"get".equals(method.getName()) || !"synthetic_link".equals(arguments[0])) {
                return refused(method.getName());
            }
            providerReads.incrementAndGet();
            return current.get();
        });
        final Resource resource = resource(counts, new AtomicReference<>(node), values, false);
        assertEquals(Optional.of(false), ReferenceProperties.mentions(resource, TARGET));
        current.set(TARGET);
        assertEquals(Optional.of(true), ReferenceProperties.mentions(resource, TARGET));
        assertEquals(2, providerReads.get());
        assertEquals(2, counts.metadata().get());
        assertEquals(2, counts.adaptations().get());
    }

    @Test
    void nativePropertyTypeIsRecheckedAfterTheCompleteMetadataPass() {
        final Counts counts = new Counts();
        final AtomicInteger typeReads = new AtomicInteger();
        final Property property = proxy(Property.class, (held, method, arguments) ->
                switch (method.getName()) {
                    case "getType" -> typeReads.incrementAndGet() == 1
                            ? PropertyType.PATH : PropertyType.BOOLEAN;
                    case "getName" -> "synthetic:link";
                    default -> refused(method.getName());
                });
        final Node node = proxy(Node.class, (held, method, arguments) -> switch (method.getName()) {
            case "getProperties" -> iterator(counts, List.of(property));
            case "getProperty" -> {
                counts.lookups().incrementAndGet();
                yield property;
            }
            default -> refused(method.getName());
        });
        assertEquals(Optional.of(false), ReferenceProperties.mentions(resource(counts,
                new AtomicReference<>(node), new ValueMapDecorator(Map.of()), false), TARGET));
        assertEquals(2, typeReads.get(), "metadata must never replace validation of the current stored type");
        assertEquals(0, counts.lookups().get(),
                "rechecking the selected property does not need another lookup");
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
            case "getProperty" -> {
                counts.lookups().incrementAndGet();
                yield properties.get(NAMES.indexOf(arguments[0]));
            }
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
                        .map(ReferencePropertyHandleReadTest::value).toArray(Value[]::new);
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
