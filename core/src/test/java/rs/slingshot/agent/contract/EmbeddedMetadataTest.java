// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import rs.slingshot.agent.route.AgentRouteTable;

/** Embedded metadata is shared only after a whole successful load in its defining bundle. */
final class EmbeddedMetadataTest {

    private static final String PRODUCT = "rs.slingshot.agent.";

    private enum Kind {
        CONTRACT(AgentContract.class.getName(), List.of(AgentContract.CONTRACT_RESOURCE.substring(1),
                AgentContract.DIGEST_RESOURCE.substring(1))),
        ROUTES(AgentRouteTable.class.getName(), List.of(AgentRouteTable.TABLE_RESOURCE.substring(1)));

        private final String type;
        private final List<String> resources;

        Kind(String type, List<String> resources) {
            this.type = type;
            this.resources = resources;
        }
    }

    private enum Fault {
        MISSING,
        CORRUPTED,
        IO_FAILURE
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void repeatedLoadsReadTheEmbeddedResourcesOnlyOnce(Kind kind) throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        final Object first = bundle.load(kind);
        assertLoaded(kind, first);
        assertSame(first, bundle.load(kind));
        assertSame(first, bundle.load(kind));
        kind.resources.forEach(resource -> assertEquals(1, bundle.reads(resource), resource));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void simultaneousFirstLoadsPublishOneWholeValue(Kind kind)
            throws ReflectiveOperationException, InterruptedException {
        final BundleLoader bundle = new BundleLoader();
        bundle.loadClass(kind.type);
        final CountDownLatch starting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            final List<Future<Object>> results = IntStream.range(0, 32).mapToObj(ignored ->
                    executor.submit(() -> {
                        assertTrue(starting.await(10, TimeUnit.SECONDS));
                        return bundle.load(kind);
                    })).toList();
            starting.countDown();
            final Object first = completed(results.getFirst());
            assertLoaded(kind, first);
            results.forEach(result -> assertSame(first, completed(result)));
            kind.resources.forEach(resource -> assertEquals(1, bundle.reads(resource), resource));
        } finally {
            starting.countDown();
        }
    }

    private static Object completed(Future<Object> result) {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (final java.util.concurrent.ExecutionException
                       | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException(failed);
        }
    }

    @ParameterizedTest
    @MethodSource("resourceFailures")
    void refusedAndThrowingLoadsAreRetriedWithoutPoisoningTheClass(Kind kind, String resource,
            Fault fault) throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        bundle.faults.put(resource, fault);
        assertFailed(bundle, kind, fault);
        assertFailed(bundle, kind, fault);
        assertEquals(2, bundle.reads(resource));
        bundle.faults.clear();
        final Object restored = bundle.load(kind);
        assertLoaded(kind, restored);
        assertSame(restored, bundle.load(kind));
        assertEquals(3, bundle.reads(resource));
    }

    private static void assertFailed(BundleLoader bundle, Kind kind, Fault fault)
            throws ReflectiveOperationException {
        if (fault == Fault.IO_FAILURE) {
            final InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                    () -> bundle.load(kind));
            assertInstanceOf(UncheckedIOException.class, failed.getCause());
            return;
        }
        final Object outcome = bundle.load(kind);
        assertEquals(kind.type + "$Refused", outcome.getClass().getName());
    }

    private static Stream<Arguments> resourceFailures() {
        return Stream.of(Kind.values()).flatMap(kind -> kind.resources.stream().flatMap(resource ->
                Stream.of(Fault.values()).map(fault -> Arguments.of(kind, resource, fault))));
    }

    @Test
    void authenticatedButUnparsableContractsAreNotRemembered() throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        final byte[] corrupt = "synthetic-corruption".getBytes(StandardCharsets.UTF_8);
        bundle.overrides.put(Kind.CONTRACT.resources.getFirst(), corrupt);
        bundle.overrides.put(Kind.CONTRACT.resources.getLast(),
                AgentContract.digestOf(corrupt).getBytes(StandardCharsets.UTF_8));
        assertFailed(bundle, Kind.CONTRACT, Fault.CORRUPTED);
        assertFailed(bundle, Kind.CONTRACT, Fault.CORRUPTED);
        bundle.overrides.clear();
        final Object restored = bundle.load(Kind.CONTRACT);
        assertLoaded(Kind.CONTRACT, restored);
        assertSame(restored, bundle.load(Kind.CONTRACT));
        Kind.CONTRACT.resources.forEach(resource -> assertEquals(3, bundle.reads(resource)));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void replacementBundlesLoadTheirOwnValues(Kind kind) throws ReflectiveOperationException {
        final BundleLoader original = new BundleLoader();
        final Object first = original.load(kind);
        final BundleLoader replacement = new BundleLoader();
        replacement.faults.put(kind.resources.getFirst(), Fault.MISSING);
        assertFailed(replacement, kind, Fault.MISSING);
        replacement.faults.clear();
        if (kind == Kind.ROUTES) {
            replacement.overrides.put(kind.resources.getFirst(), new String(
                    originalBytes(kind.resources.getFirst()), StandardCharsets.UTF_8)
                    .replace("application/json", "application/synthetic").getBytes(StandardCharsets.UTF_8));
        }
        final Object second = replacement.load(kind);
        assertLoaded(kind, second);
        assertNotEquals(first.getClass(), second.getClass());
        assertSame(first, original.load(kind));
        assertSame(second, replacement.load(kind));
        kind.resources.forEach(resource -> assertEquals(1, original.reads(resource)));
        assertEquals(2, replacement.reads(kind.resources.getFirst()));
        if (kind == Kind.ROUTES) {
            assertEquals("application/synthetic", mediaType(second));
            assertEquals("application/json", mediaType(first));
        }
    }

    @Test
    void sharedTableViewsCannotChangeTheLoadedRoutesOrAliases() {
        final AgentRouteTable table = assertInstanceOf(AgentRouteTable.Loaded.class,
                AgentRouteTable.load()).table();
        final List<String> names = table.names();
        table.routes().clear();
        assertEquals(names, table.names());
        assertThrows(UnsupportedOperationException.class, () -> table.names().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.aliases().clear());
        assertSame(table, assertInstanceOf(AgentRouteTable.Loaded.class, AgentRouteTable.load()).table());
    }

    @Test
    void callerContractBytesRemainAuthenticatedAndParsedSeparately() throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        final Object embedded = bundle.load(Kind.CONTRACT);
        final Class<?> type = bundle.loadClass(Kind.CONTRACT.type);
        final byte[] bytes = originalBytes(Kind.CONTRACT.resources.getFirst());
        final Object refused = type.getMethod("load", byte[].class, String.class)
                .invoke(null, bytes, "0".repeat(64));
        assertEquals(Kind.CONTRACT.type + "$Refused", refused.getClass().getName());
        assertEquals("DIGEST_MISMATCH", refused.getClass().getMethod("failure").invoke(refused).toString());
        final Object parsed = type.getMethod("load", byte[].class, String.class)
                .invoke(null, bytes, AgentContract.digestOf(bytes));
        assertLoaded(Kind.CONTRACT, parsed);
        assertNotEquals(embedded, parsed);
        assertSame(embedded, bundle.load(Kind.CONTRACT));
    }

    @Test
    void callerRouteDocumentsRemainParsedSeparately() throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        final Object embedded = bundle.load(Kind.ROUTES);
        final Class<?> type = bundle.loadClass(Kind.ROUTES.type);
        final Object refused = type.getMethod("read", String.class).invoke(null, "synthetic-corruption");
        assertEquals(Kind.ROUTES.type + "$Refused", refused.getClass().getName());
        final String document = new String(originalBytes(Kind.ROUTES.resources.getFirst()),
                StandardCharsets.UTF_8).replace("application/json", "application/synthetic");
        final Object parsed = type.getMethod("read", String.class).invoke(null, document);
        assertLoaded(Kind.ROUTES, parsed);
        assertEquals("application/synthetic", mediaType(parsed));
        assertEquals("application/json", mediaType(embedded));
        assertSame(embedded, bundle.load(Kind.ROUTES));
    }

    private static String mediaType(Object loaded) throws ReflectiveOperationException {
        final Object table = loaded.getClass().getMethod("table").invoke(loaded);
        final Object route = table.getClass().getMethod("route", String.class).invoke(table, "capabilities");
        return (String) route.getClass().getMethod("mediaType").invoke(route);
    }

    private static void assertLoaded(Kind kind, Object outcome) {
        assertEquals(kind.type + "$Loaded", outcome.getClass().getName());
    }

    private static byte[] originalBytes(String resource) {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("synthetic test resource is absent");
            }
            return input.readAllBytes();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** A fresh defining loader models bundle replacement without resetting product fields. */
    private static final class BundleLoader extends ClassLoader {
        private final Map<String, Fault> faults = new ConcurrentHashMap<>();
        private final Map<String, byte[]> overrides = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final ReentrantLock loading = new ReentrantLock();
        private final Set<String> resources = Set.copyOf(Stream.of(Kind.values())
                .flatMap(kind -> kind.resources.stream()).toList());

        private BundleLoader() {
            super(Thread.currentThread().getContextClassLoader());
        }

        private Object load(Kind kind) throws ReflectiveOperationException {
            return loadClass(kind.type).getMethod("load").invoke(null);
        }

        private int reads(String resource) {
            return counts.getOrDefault(resource, new AtomicInteger()).get();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            loading.lock();
            try {
                return productClass(name, resolve);
            } finally {
                loading.unlock();
            }
        }

        private Class<?> productClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PRODUCT)) {
                return super.loadClass(name, resolve);
            }
            Class<?> held = findLoadedClass(name);
            if (held == null) {
                try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) {
                        throw new ClassNotFoundException(name);
                    }
                    final byte[] bytes = input.readAllBytes();
                    held = defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failed) {
                    throw new ClassNotFoundException(name, failed);
                }
            }
            if (resolve) {
                resolveClass(held);
            }
            return held;
        }

        @Override
        public InputStream getResourceAsStream(String resource) {
            if (resources.contains(resource)) {
                counts.computeIfAbsent(resource, ignored -> new AtomicInteger()).incrementAndGet();
            }
            final Fault fault = faults.get(resource);
            if (fault == Fault.MISSING) {
                return null;
            }
            if (fault == Fault.IO_FAILURE) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("synthetic resource failure");
                    }
                };
            }
            if (fault == Fault.CORRUPTED) {
                return new ByteArrayInputStream("synthetic-corruption".getBytes(StandardCharsets.UTF_8));
            }
            final byte[] bytes = overrides.get(resource);
            return bytes == null ? getParent().getResourceAsStream(resource)
                    : new ByteArrayInputStream(bytes);
        }
    }
}
