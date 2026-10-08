// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import rs.slingshot.agent.contract.AgentContract;

/** Bundle-local immutable metadata and the dynamic observations it must never remember. */
@ExtendWith(SlingContextExtension.class)
final class CapabilityMetadataTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private static final String PRODUCT = "rs.slingshot.agent.";
    private static final List<String> RESOURCES = List.of(
            AgentContract.CONTRACT_RESOURCE.substring(1), AgentContract.DIGEST_RESOURCE.substring(1),
            CapabilityServlet.CANONICAL_CONTRACT_RESOURCE.substring(1),
            CapabilityServlet.CANONICAL_DIGEST_RESOURCE.substring(1),
            AgentContract.TRANSPORT_DIGEST_RESOURCE.substring(1));

    private enum Fault {
        MISSING,
        CORRUPTED
    }

    @Test
    void repeatedDocumentsAuthenticateTheEmbeddedMetadataOnlyOnce() throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        final Discovery discovery = new Discovery(bundle);
        final String first = discovery.document(List.of());
        assertEquals(first, discovery.document(List.of()));
        assertEquals(first, discovery.document(List.of()));
        RESOURCES.forEach(resource -> assertEquals(1, bundle.reads(resource), resource));
        assertEquals(3, discovery.readinessObservations.get());
    }

    @Test
    void simultaneousFirstRequestsPublishOneAuthenticatedMetadataSet()
            throws ReflectiveOperationException, InterruptedException, ExecutionException, TimeoutException {
        final BundleLoader bundle = new BundleLoader();
        final Discovery discovery = new Discovery(bundle);
        final CountDownLatch starting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            final List<Future<String>> documents = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(ignored -> executor.submit(() -> {
                        assertTrue(starting.await(10, TimeUnit.SECONDS));
                        return discovery.document(List.of());
                    })).toList();
            starting.countDown();
            final String first = documents.getFirst().get(10, TimeUnit.SECONDS);
            documents.forEach(document -> assertEquals(first, completed(document)));
            RESOURCES.forEach(resource -> assertEquals(1, bundle.reads(resource), resource));
            assertEquals(32, discovery.readinessObservations.get());
        } finally {
            starting.countDown();
        }
    }

    private static String completed(Future<String> document) {
        try {
            return document.get(10, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (final java.util.concurrent.ExecutionException
                       | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException(failed);
        }
    }

    @Test
    void readinessGenerationAndRuntimeCommandsRemainFresh()
            throws ReflectiveOperationException, RepositoryException {
        final Discovery discovery = new Discovery(new BundleLoader());
        final Object runtime = discovery.bundle.loadClass(PRODUCT + "http.DefaultCommandRuntime")
                .getConstructor().newInstance();
        runtime.getClass().getMethod("activate").invoke(runtime);
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class));
        session.getRootNode().addNode("var").addNode("slingshot-agent");
        session.save();
        try (ResourceResolver isolated = isolated(sling.resourceResolver())) {
            final List<?> commands =
                    (List<?>) runtime.getClass().getMethod("commandContracts").invoke(runtime);
            assertFalse(commands.isEmpty());
            discovery.lifecycle("READY", 17, isolated);
            discovery.readiness.set("READY");
            final String first = discovery.document(commands);
            assertTrue(first.contains("\"agent_event_store_generation\":17"), first);
            assertTrue(first.contains("\"continuation_authority_ready\":true"), first);
            assertTrue(first.contains("query_paths"), first);
            discovery.lifecycle("READY", 29, isolated);
            discovery.readiness.set("NOT_READY");
            final String second = discovery.document(List.of());
            assertTrue(second.contains("\"agent_event_store_generation\":29"), second);
            assertTrue(second.contains("\"continuation_authority_ready\":false"), second);
            assertTrue(second.contains("\"command_contracts\":[]"), second);
            assertNotEquals(first, second);
            discovery.lifecycle("UNAVAILABLE", 29, isolated);
            assertTrue(discovery.document(commands).contains("\"agent_event_store_generation\":1"));
            assertEquals(3, discovery.readinessObservations.get());
        } finally {
            discovery.stop();
            runtime.getClass().getMethod("deactivate").invoke(runtime);
        }
    }

    private static ResourceResolver isolated(ResourceResolver shared) {
        return (ResourceResolver) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] { ResourceResolver.class }, (proxy, method, arguments) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(shared, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    @Test
    void aNewBundleLoaderCannotReuseThePreviousBundlesMetadata() throws ReflectiveOperationException {
        final Discovery original = new Discovery(new BundleLoader());
        final String first = original.document(List.of());
        final BundleLoader replacement = new BundleLoader();
        replacement.overrides.put(AgentContract.TRANSPORT_DIGEST_RESOURCE.substring(1),
                "1".repeat(64).getBytes(StandardCharsets.UTF_8));
        final Discovery updated = new Discovery(replacement);
        final String second = updated.document(List.of());
        assertTrue(second.contains("\"transport_contract_digest\":\"" + "1".repeat(64) + "\""));
        assertNotEquals(first, second);
        assertEquals(first, original.document(List.of()));
        RESOURCES.forEach(resource -> assertEquals(1, replacement.reads(resource), resource));
    }

    @ParameterizedTest
    @MethodSource("resourceFailures")
    void aFailedLoadIsNeverPublishedOrTurnedIntoAClassInitializationFailure(String resource,
            Fault fault) throws ReflectiveOperationException {
        final BundleLoader bundle = new BundleLoader();
        if (fault == Fault.MISSING) {
            bundle.missing.add(resource);
        } else {
            bundle.overrides.put(resource, "synthetic-corruption".getBytes(StandardCharsets.UTF_8));
        }
        final Discovery discovery = new Discovery(bundle);
        final InvocationTargetException first = assertThrows(InvocationTargetException.class,
                () -> discovery.document(List.of()));
        final InvocationTargetException second = assertThrows(InvocationTargetException.class,
                () -> discovery.document(List.of()));
        assertInstanceOf(IllegalStateException.class, first.getCause());
        assertInstanceOf(IllegalStateException.class, second.getCause());
        assertEquals(first.getCause().getMessage(), second.getCause().getMessage());
        assertEquals(0, discovery.readinessObservations.get());
        assertTrue(bundle.reads(resource) >= 2);
        bundle.missing.clear();
        bundle.overrides.clear();
        assertTrue(discovery.document(List.of()).contains("\"command_contracts\":[]"));
        final int authenticatedReads = bundle.reads(resource);
        discovery.document(List.of());
        assertEquals(authenticatedReads, bundle.reads(resource));
        assertEquals(2, discovery.readinessObservations.get());
    }

    private static Stream<Arguments> resourceFailures() {
        return RESOURCES.stream().flatMap(resource -> Stream.of(Fault.values())
                .map(fault -> Arguments.of(resource, fault)));
    }

    @Test
    void embeddedMetadataReuseNeverBypassesAuthenticationOfCallerSuppliedContractBytes()
            throws ReflectiveOperationException {
        final Discovery discovery = new Discovery(new BundleLoader());
        discovery.document(List.of());
        final Class<?> contract = discovery.bundle.loadClass(PRODUCT + "contract.AgentContract");
        final byte[] bytes = "synthetic-contract".getBytes(StandardCharsets.UTF_8);
        final Object refused = contract.getMethod("load", byte[].class, String.class)
                .invoke(null, bytes, "0".repeat(64));
        assertEquals(PRODUCT + "contract.AgentContract$Refused", refused.getClass().getName());
        assertEquals("DIGEST_MISMATCH", refused.getClass().getMethod("failure").invoke(refused).toString());
    }

    /** A fresh product classloader models an installed bundle and permits resource faults locally. */
    private static final class BundleLoader extends ClassLoader {
        private final Map<String, byte[]> overrides = new ConcurrentHashMap<>();
        private final Set<String> missing = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final ReentrantLock loading = new ReentrantLock();

        private BundleLoader() {
            super(Thread.currentThread().getContextClassLoader());
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
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) {
                        throw new ClassNotFoundException(name);
                    }
                    final byte[] bytes = input.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (final IOException failed) {
                    throw new ClassNotFoundException(name, failed);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }

        @Override
        public InputStream getResourceAsStream(String resource) {
            if (RESOURCES.contains(resource)) {
                counts.computeIfAbsent(resource, ignored -> new AtomicInteger()).incrementAndGet();
            }
            if (missing.contains(resource)) {
                return null;
            }
            final byte[] overridden = overrides.get(resource);
            return overridden == null ? getParent().getResourceAsStream(resource)
                    : new ByteArrayInputStream(overridden);
        }
    }

    /** Reflection keeps every product argument inside its own isolated bundle loader. */
    private static final class Discovery {
        private final BundleLoader bundle;
        private final Class<?> servlet;
        private final Class<?> readinessType;
        private final Object observing;
        private final Object lifecycle;
        private final AtomicReference<String> readiness = new AtomicReference<>("NOT_READY");
        private final AtomicInteger readinessObservations = new AtomicInteger();

        private Discovery(BundleLoader bundle) throws ReflectiveOperationException {
            this.bundle = bundle;
            servlet = bundle.loadClass(PRODUCT + "http.CapabilityServlet");
            readinessType = bundle.loadClass(PRODUCT + "discovery.AdvertisedCapabilities$Readiness");
            lifecycle = bundle.loadClass(PRODUCT + "store.StateLifecycleService")
                    .getConstructor().newInstance();
            observing = Proxy.newProxyInstance(bundle, new Class<?>[] { readinessType },
                    (proxy, method, arguments) -> {
                        readinessObservations.incrementAndGet();
                        return bundle.loadClass(PRODUCT
                                        + "discovery.AdvertisedCapabilities$ContinuationAuthority")
                                .getField(readiness.get()).get(null);
                    });
        }

        private String document(List<?> commands) throws ReflectiveOperationException {
            final Object document = servlet.getMethod("document", readinessType, List.class)
                    .invoke(null, observing, commands);
            return (String) document.getClass().getMethod("render").invoke(document);
        }

        private void lifecycle(String availability, long generation, ResourceResolver resolver)
                throws ReflectiveOperationException, javax.jcr.RepositoryException {
            stop();
            if ("UNAVAILABLE".equals(availability)) {
                return;
            }
            final Session session = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            bundle.loadClass(PRODUCT + "store.GenerationStore").getMethod("establish", Session.class)
                    .invoke(null, session);
            final var record = session.getNode("/var/slingshot-agent/generation");
            record.setProperty("serving", generation);
            record.setProperty("served", new javax.jcr.Value[] {
                    session.getValueFactory().createValue(1L),
                    session.getValueFactory().createValue(generation)
            });
            session.save();
            final Class<?> sourceType =
                    bundle.loadClass(PRODUCT + "repository.AgentSession$ServiceSessionSource");
            final Object source = Proxy.newProxyInstance(bundle, new Class<?>[] { sourceType },
                    (proxy, method, arguments) -> resolver);
            final Class<?> sessions = bundle.loadClass(PRODUCT + "repository.AgentSession");
            lifecycle.getClass().getMethod("available", sessions)
                    .invoke(lifecycle, sessions.getConstructor(sourceType).newInstance(source));
            lifecycle.getClass().getMethod("activate").invoke(lifecycle);
            final Object snapshot = lifecycle.getClass().getMethod("observed").invoke(null);
            assertEquals("READY", snapshot.getClass().getMethod("availability").invoke(snapshot).toString());
            assertEquals(generation, snapshot.getClass().getMethod("generation").invoke(snapshot));
        }

        private void stop() throws ReflectiveOperationException {
            lifecycle.getClass().getMethod("deactivate").invoke(lifecycle);
        }
    }
}
