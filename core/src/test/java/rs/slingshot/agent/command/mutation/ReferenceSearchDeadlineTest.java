// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import rs.slingshot.agent.stream.ElapsedTime;

/** Reference completeness cannot outlive the shared monotonic search deadline. */
final class ReferenceSearchDeadlineTest {

    private static final String TARGET = "/content/synthetic-deadline-target";
    private static final String OTHER = "/content/synthetic-deadline-other";

    @Test
    void anExactDeadlineStillAllowsCompleteInspection() {
        inspect(RepositoryReach.SEARCH_MILLISECONDS, RepositoryReach.Completeness.COMPLETE, 2);
    }

    @Test
    void aPropertyReadCrossingTheDeadlineMustLeaveDiscoveryIncomplete() {
        inspect(RepositoryReach.SEARCH_MILLISECONDS + 1, RepositoryReach.Completeness.INCOMPLETE, 1);
    }

    private static void inspect(long afterRead, RepositoryReach.Completeness expected, int expectedReads) {
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger reads = new AtomicInteger();
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("first", OTHER);
        properties.put("second", OTHER);
        final ValueMap values = new ValueMapDecorator(properties) {
            @Override
            public Object get(Object key) {
                reads.incrementAndGet();
                clock.set(afterRead);
                return super.get(key);
            }
        };
        final Resource root = proxy(Resource.class, (held, method, arguments) -> switch (method.getName()) {
            case "getPath" -> RepositoryReach.CONTENT_ROOT;
            case "adaptTo" -> null;
            case "getValueMap" -> values;
            case "listChildren" -> Collections.emptyIterator();
            default -> refused(method.getName());
        });
        try (ResourceResolver resolver = proxy(ResourceResolver.class,
                (held, method, arguments) -> switch (method.getName()) {
                    case "getResource" -> root;
                    case "close" -> null;
                    default -> refused(method.getName());
                })) {
            final RepositoryReach.References answer = RepositoryReach.references(resolver, TARGET, 100,
                    ElapsedTime.start(clock::get));

            assertEquals(expected, answer.completeness(),
                    "a final property read exceeded the search deadline");
            assertEquals(expectedReads, reads.get(), "later properties were read after the shared deadline");
            assertEquals(Collections.emptyList(), answer.found());
        }
    }

    private enum ReadPoint {
        ADAPTATION, PROPERTIES, ITERATOR_HAS_NEXT, ITERATOR_NEXT, TYPE, NAME,
        VALUE_MAP, PROPERTY, MULTIPLICITY, SCALAR_VALUE, SEQUENCE_VALUES, TEXT,
        LEGACY_KEYS, LEGACY_VALUE, LEGACY_ITERATOR, LEGACY_HAS_NEXT, LEGACY_NEXT
    }

    private enum Demand {
        ALL, FIRST
    }

    private enum Boundary {
        INCLUSIVE, EXPIRED
    }

    @ParameterizedTest
    @EnumSource(value = ReadPoint.class, names = {"ADAPTATION", "PROPERTIES", "ITERATOR_HAS_NEXT",
            "ITERATOR_NEXT", "TYPE", "NAME", "VALUE_MAP", "MULTIPLICITY", "SCALAR_VALUE",
            "SEQUENCE_VALUES", "TEXT"})
    void allReferenceInspectionStopsAfterTheFirstExpiredProviderCall(ReadPoint point) {
        inspectProvider(point, Demand.ALL);
    }

    @ParameterizedTest
    @EnumSource(value = ReadPoint.class, names = {"ADAPTATION", "PROPERTIES", "ITERATOR_HAS_NEXT",
            "ITERATOR_NEXT", "TYPE", "NAME", "VALUE_MAP", "PROPERTY", "MULTIPLICITY",
            "SCALAR_VALUE", "SEQUENCE_VALUES", "TEXT"})
    void decisiveDeletionInspectionStopsAfterTheFirstExpiredProviderCall(ReadPoint point) {
        inspectProvider(point, Demand.FIRST);
    }

    @ParameterizedTest
    @EnumSource(value = ReadPoint.class, names = {"LEGACY_KEYS", "LEGACY_VALUE", "LEGACY_ITERATOR",
            "LEGACY_HAS_NEXT", "LEGACY_NEXT"})
    void legacyInspectionStopsAfterTheFirstExpiredProviderCall(ReadPoint point) {
        inspectProvider(point, Demand.ALL);
        inspectProvider(point, Demand.FIRST);
    }

    private static void inspectProvider(ReadPoint point, Demand demand) {
        for (final Boundary boundary : Boundary.values()) {
            final Provider provider = new Provider(point, boundary);
            final ElapsedTime elapsed = ElapsedTime.start(provider.clock::get);
            final java.util.Optional<Boolean> answer = demand == Demand.ALL
                    ? ReferenceProperties.mentions(provider.resource(), TARGET, elapsed)
                    : ReferenceProperties.firstMention(provider.resource(), TARGET, elapsed);
            assertEquals(true, provider.reached, "the synthetic provider boundary was never reached");
            assertEquals(boundary == Boundary.INCLUSIVE ? java.util.Optional.of(false)
                    : java.util.Optional.empty(), answer, "incomplete inspection claimed an answer");
            assertEquals(0, provider.afterExpiry.get(),
                    "another provider call followed the deadline crossing");
        }
    }

    private static final class Provider {

        private final ReadPoint point;
        private final Boundary boundary;
        private final AtomicLong clock = new AtomicLong();
        private final AtomicInteger afterExpiry = new AtomicInteger();
        private boolean reached;

        private Provider(ReadPoint point, Boundary boundary) {
            this.point = point;
            this.boundary = boundary;
        }

        private void called(ReadPoint current) {
            if (clock.get() > RepositoryReach.SEARCH_MILLISECONDS) {
                afterExpiry.incrementAndGet();
            }
            if (current == point && !reached) {
                reached = true;
                clock.set(RepositoryReach.SEARCH_MILLISECONDS + (boundary == Boundary.EXPIRED ? 1 : 0));
            }
        }

        private Resource resource() {
            final boolean legacy = java.util.EnumSet.of(ReadPoint.LEGACY_KEYS, ReadPoint.LEGACY_VALUE,
                    ReadPoint.LEGACY_ITERATOR, ReadPoint.LEGACY_HAS_NEXT,
                            ReadPoint.LEGACY_NEXT).contains(point);
            final javax.jcr.Node node = node();
            final Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("first", OTHER);
            properties.put("second", OTHER);
            final ValueMap values = new ValueMapDecorator(properties) {
                @Override
                public java.util.Set<String> keySet() {
                    final java.util.Set<String> keys = super.keySet();
                    called(ReadPoint.LEGACY_KEYS);
                    return new java.util.AbstractSet<>() {
                        @Override
                        public int size() {
                            return keys.size();
                        }

                        @Override
                        public java.util.Iterator<String> iterator() {
                            called(ReadPoint.LEGACY_ITERATOR);
                            final java.util.Iterator<String> iterator = keys.iterator();
                            return new java.util.Iterator<>() {
                                @Override
                                public boolean hasNext() {
                                    called(ReadPoint.LEGACY_HAS_NEXT);
                                    return iterator.hasNext();
                                }

                                @Override
                                public String next() {
                                    called(ReadPoint.LEGACY_NEXT);
                                    return iterator.next();
                                }
                            };
                        }
                    };
                }

                @Override
                public Object get(Object key) {
                    called(ReadPoint.LEGACY_VALUE);
                    return super.get(key);
                }
            };
            return proxy(Resource.class, (held, method, arguments) -> switch (method.getName()) {
                case "adaptTo" -> {
                    called(ReadPoint.ADAPTATION);
                    yield legacy ? null : node;
                }
                case "getValueMap" -> {
                    called(ReadPoint.VALUE_MAP);
                    yield values;
                }
                default -> refused(method.getName());
            });
        }

        private javax.jcr.Node node() {
            final javax.jcr.Property property = property();
            return proxy(javax.jcr.Node.class, (held, method, arguments) -> switch (method.getName()) {
                case "getProperties" -> {
                    called(ReadPoint.PROPERTIES);
                    yield iterator(property);
                }
                case "getProperty" -> {
                    called(ReadPoint.PROPERTY);
                    yield property;
                }
                default -> refused(method.getName());
            });
        }

        private javax.jcr.PropertyIterator iterator(javax.jcr.Property property) {
            final java.util.Iterator<javax.jcr.Property> iterator = java.util.List.of(property,
                    property).iterator();
            return proxy(javax.jcr.PropertyIterator.class, (held, method, arguments) -> switch
            (method.getName()) {
                case "hasNext" -> {
                    called(ReadPoint.ITERATOR_HAS_NEXT);
                    yield iterator.hasNext();
                }
                case "nextProperty", "next" -> {
                    called(ReadPoint.ITERATOR_NEXT);
                    yield iterator.next();
                }
                default -> refused(method.getName());
            });
        }

        private javax.jcr.Property property() {
            final javax.jcr.Value value = proxy(javax.jcr.Value.class, (held, method, arguments) -> {
                called(ReadPoint.TEXT);
                return "getString".equals(method.getName()) ? OTHER : refused(method.getName());
            });
            return proxy(javax.jcr.Property.class, (held, method, arguments) -> switch (method.getName()) {
                case "getType" -> {
                    called(ReadPoint.TYPE);
                    yield javax.jcr.PropertyType.PATH;
                }
                case "getName" -> {
                    called(ReadPoint.NAME);
                    yield "synthetic:link";
                }
                case "isMultiple" -> {
                    called(ReadPoint.MULTIPLICITY);
                    yield point != ReadPoint.SCALAR_VALUE;
                }
                case "getValue" -> {
                    called(ReadPoint.SCALAR_VALUE);
                    yield value;
                }
                case "getValues" -> {
                    called(ReadPoint.SEQUENCE_VALUES);
                    yield new javax.jcr.Value[]{value, value};
                }
                default -> refused(method.getName());
            });
        }
    }

    private enum WalkPoint {
        ROOT, ABSENT_ROOT, PATH, CHILDREN, CHILD_HAS_NEXT, CHILD_NEXT
    }

    @ParameterizedTest
    @EnumSource(WalkPoint.class)
    void traversalProviderCallsShareTheSameInclusiveDeadline(WalkPoint point) {
        for (final Boundary boundary : Boundary.values()) {
            final WalkProvider provider = new WalkProvider(point, boundary);
            final RepositoryReach.References answer = RepositoryReach.references(provider.resolver(), TARGET,
                    100,
                    ElapsedTime.start(provider.clock::get));
            assertEquals(true, provider.reached, "the synthetic traversal boundary was never reached");
            assertEquals(boundary == Boundary.INCLUSIVE ? RepositoryReach.Completeness.COMPLETE
                    : RepositoryReach.Completeness.INCOMPLETE, answer.completeness());
            assertEquals(0, provider.afterExpiry.get(), "traversal called another provider after expiry");
        }
    }

    private static final class WalkProvider {

        private final WalkPoint point;
        private final Boundary boundary;
        private final AtomicLong clock = new AtomicLong();
        private final AtomicInteger afterExpiry = new AtomicInteger();
        private boolean reached;

        private WalkProvider(WalkPoint point, Boundary boundary) {
            this.point = point;
            this.boundary = boundary;
        }

        private void called(WalkPoint current) {
            if (clock.get() > RepositoryReach.SEARCH_MILLISECONDS) {
                afterExpiry.incrementAndGet();
            }
            if (current == point && !reached) {
                reached = true;
                clock.set(RepositoryReach.SEARCH_MILLISECONDS + (boundary == Boundary.EXPIRED ? 1 : 0));
            }
        }

        private ResourceResolver resolver() {
            final Resource root = resource(RepositoryReach.CONTENT_ROOT);
            return proxy(ResourceResolver.class, (held, method, arguments) -> {
                called(point == WalkPoint.ABSENT_ROOT ? WalkPoint.ABSENT_ROOT : WalkPoint.ROOT);
                return "getResource".equals(method.getName())
                        ? point == WalkPoint.ABSENT_ROOT ? null : root : refused(method.getName());
            });
        }

        private Resource resource(String path) {
            return proxy(Resource.class, (held, method, arguments) -> switch (method.getName()) {
                case "getPath" -> {
                    called(WalkPoint.PATH);
                    yield path;
                }
                case "listChildren" -> {
                    called(WalkPoint.CHILDREN);
                    yield RepositoryReach.CONTENT_ROOT.equals(path) ? children() :
                            Collections.emptyIterator();
                }
                case "adaptTo" -> {
                    called(WalkPoint.PATH);
                    yield null;
                }
                case "getValueMap" -> {
                    called(WalkPoint.PATH);
                    yield new ValueMapDecorator(Map.of());
                }
                default -> refused(method.getName());
            });
        }

        private java.util.Iterator<Resource> children() {
            final java.util.Iterator<Resource> iterator = java.util.List.of(
                    resource("/content/synthetic-deadline-child")).iterator();
            return new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    called(WalkPoint.CHILD_HAS_NEXT);
                    return iterator.hasNext();
                }

                @Override
                public Resource next() {
                    called(WalkPoint.CHILD_NEXT);
                    return iterator.next();
                }
            };
        }
    }

    private static <Type> Type proxy(Class<Type> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type}, handler));
    }

    private static Object refused(String method) {
        throw new AssertionError("unexpected synthetic deadline read: " + method);
    }
}
