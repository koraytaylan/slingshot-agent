// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;

/** Manifest allocation and lexical ownership through uncertain native saves. */
@ExtendWith(SlingContextExtension.class)
final class CapacityReservationBatchTest {

    private static final AgentContract CONTRACT =
            assertInstanceOf(AgentContract.Loaded.class, AgentContract.load()).contract();

    private static final StatePath.Caller CALLER =
            assertInstanceOf(StatePath.Held.class, StatePath.caller("reservation-owner")).caller();

    private static final UUID OWNER = UUID.fromString("4c82f95c-3c27-4a74-a845-8a43b5bce27e");

    private static final List<CapacityReservation.Charge> CHARGES = List.of(
            new CapacityReservation.Charge(AccountedQuantity.EVENT_ROWS, 1),
            new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES, 23));

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void batchOwnsCommittedIdentitiesWhenAnyCreationReplyIsLost() throws RepositoryException {
        final Session session = prepared();
        for (final int failAt : List.of(1, 2, 4)) {
            final var saves = new java.util.concurrent.atomic.AtomicInteger();
            final Session uncertain = lostCreationReply(session, saves, failAt);
            assertThrows(RepositoryException.class, () -> {
                try (CapacityReservation.Batch batch = CapacityReservation.batch(uncertain, CONTRACT)) {
                    for (final List<CapacityReservation.Charge> charges : List.of(CHARGES, CHARGES,
                            CHARGES, CHARGES)) {
                        batch.create(CALLER, charges).orElseThrow();
                    }
                }
            });
            assertTrue(CapacityReservation.inventory(session).isEmpty());
            counts(session, 0, 0);
        }
    }

    @Test
    void wholeManifestIsVisibleInOneSaveWithoutCharging() throws RepositoryException, LoginException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        final List<CapacityReservation.Charge> other = List.of(
                new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES, 47),
                new CapacityReservation.Charge(AccountedQuantity.EVENT_ROWS, 2));
        try (CapacityReservation.Batch batch = CapacityReservation.batch(countedSaves(session, saves),
                CONTRACT)) {
            final List<CapacityReservation> allocated = batch.createAll(CALLER, List.of(CHARGES, other))
                    .orElseThrow();
            assertEquals(1, saves.get());
            assertEquals(2, allocated.size());
            assertEquals(2, allocated.stream().map(CapacityReservation::identifier).distinct().count());
            assertTrue(allocated.getFirst().charges().containsAll(CHARGES));
            assertEquals(47, allocated.getLast().charges().getFirst().amount());
            assertThrows(UnsupportedOperationException.class, allocated::clear);
            try (ResourceResolver independent = anotherResolver()) {
                assertEquals(allocated, allocated.stream().map(reservation -> {
                    try {
                        return CapacityReservation.read(session(independent), reservation.path())
                                .orElseThrow();
                    } catch (final RepositoryException failed) {
                        throw new IllegalStateException(failed);
                    }
                }).toList());
            }
            counts(session, 0, 0);
        }
        assertTrue(CapacityReservation.inventory(session).isEmpty());
    }

    @Test
    void wholeManifestLostReplyLeavesNoUnownedPendingRows() throws RepositoryException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(RepositoryException.class, () -> {
            try (CapacityReservation.Batch batch = CapacityReservation.batch(
                    lostCreationReply(session, saves, 1), CONTRACT)) {
                batch.createAll(CALLER, List.of(CHARGES, CHARGES, CHARGES)).orElseThrow();
            }
        });
        assertTrue(CapacityReservation.inventory(session).isEmpty());
        counts(session, 0, 0);
    }

    @Test
    void malformedManifestIsRejectedBeforeAnyIdentityIsStaged() throws RepositoryException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        try (CapacityReservation.Batch batch = CapacityReservation.batch(countedSaves(session, saves),
                CONTRACT)) {
            assertThrows(IllegalArgumentException.class, () -> batch.createAll(CALLER, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> batch.createAll(CALLER, List.of(CHARGES, List.of())));
            assertThrows(IllegalArgumentException.class,
                    () -> batch.createAll(CALLER, List.of(CHARGES, List.of(CHARGES.getFirst(),
                            CHARGES.getFirst()))));
            assertEquals(0, saves.get());
            assertTrue(batch.reservations().isEmpty());
            assertFalse(session.hasPendingChanges());
        }
        assertTrue(CapacityReservation.inventory(session).isEmpty());
    }

    @Test
    void wholeManifestSaveFailureDiscardsEveryStagedIdentity() throws RepositoryException {
        final Session session = prepared();
        final var failures = new java.util.concurrent.atomic.AtomicInteger(1);
        assertThrows(RepositoryException.class, () -> {
            try (CapacityReservation.Batch batch = CapacityReservation.batch(failingSaves(session, failures),
                    CONTRACT)) {
                batch.createAll(CALLER, List.of(CHARGES, CHARGES, CHARGES)).orElseThrow();
            }
        });
        assertFalse(session.hasPendingChanges());
        assertTrue(CapacityReservation.inventory(session).isEmpty());
        counts(session, 0, 0);
    }

    @Test
    void wholeManifestContentionKeepsTheOriginalAttemptBound() throws RepositoryException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        final Session contended = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        saves.incrementAndGet();
                        throw new InvalidItemStateException("synthetic manifest conflict");
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        try (CapacityReservation.Batch batch = CapacityReservation.batch(contended, CONTRACT)) {
            assertTrue(batch.createAll(CALLER, List.of(CHARGES, CHARGES, CHARGES)).isEmpty());
            assertEquals(CompareAndSet.ATTEMPTS, saves.get());
            assertTrue(batch.reservations().isEmpty());
            assertFalse(session.hasPendingChanges());
        }
        assertTrue(CapacityReservation.inventory(session).isEmpty());
        counts(session, 0, 0);
    }

    @Test
    void wholeManifestCleanupKeepsPublishedCapacityAndCancelsTheRest() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation.Batch batch = CapacityReservation.batch(session, CONTRACT);
        try (batch) {
            final List<CapacityReservation> allocated = batch.createAll(CALLER, List.of(CHARGES, CHARGES))
                    .orElseThrow();
            assertInstanceOf(CapacityLedger.Admitted.class,
                    CapacityLedger.reserve(session, allocated, CONTRACT));
            final Node source = session.getNode(StatePath.ROOT).addNode("source", "nt:unstructured");
            CapacityReservation.retain(session, allocated.getFirst(), source);
            session.save();
        }
        counts(session, 1, 23);
        assertEquals(1, CapacityReservation.inventory(session).size());
        assertThrows(IllegalStateException.class, () -> batch.createAll(CALLER, List.of(CHARGES)));
    }

    private static Session countedSaves(Session session, java.util.concurrent.atomic.AtomicInteger saves) {
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        saves.incrementAndGet();
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    @Test
    void anIndependentPendingWriterConflictsWithTheWholeManifestFence()
            throws RepositoryException, LoginException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        try (ResourceResolver independent = anotherResolver()) {
            final Session interleaved = (Session) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                    (proxy, method, arguments) -> {
                        if ("save".equals(method.getName()) && saves.incrementAndGet() == 1) {
                            CapacityReservation.create(session(independent), OWNER, CALLER, CHARGES)
                                    .orElseThrow();
                        }
                        try {
                            return method.invoke(session, arguments);
                        } catch (final InvocationTargetException failed) {
                            throw failed.getCause();
                        }
                    });
            try (CapacityReservation.Batch batch = CapacityReservation.batch(interleaved, CONTRACT)) {
                final List<CapacityReservation> allocated = batch.createAll(CALLER, List.of(CHARGES, CHARGES))
                        .orElseThrow();
                assertEquals(2, saves.get());
                assertEquals(2, allocated.size());
                assertEquals(3, CapacityReservation.inventory(session).size());
                counts(session, 0, 0);
            }
            assertEquals(1, CapacityReservation.inventory(session).size());
            assertEquals(OWNER, CapacityReservation.inventory(session).getFirst().owner());
        }
    }

    @Test
    void uncertainCommittedContentionRecognisesTheSameWholeManifest() throws RepositoryException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        final Session uncertain = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    try {
                        final Object result = method.invoke(session, arguments);
                        if ("save".equals(method.getName()) && saves.incrementAndGet() == 1) {
                            throw new InvalidItemStateException("synthetic committed contention reply");
                        }
                        return result;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        try (CapacityReservation.Batch batch = CapacityReservation.batch(uncertain, CONTRACT)) {
            final List<CapacityReservation> allocated = batch.createAll(CALLER, List.of(CHARGES, CHARGES))
                    .orElseThrow();
            assertEquals(1, saves.get());
            assertEquals(2, allocated.size());
            assertTrue(CapacityReservation.inventory(session).containsAll(allocated));
            assertEquals(2, CapacityReservation.inventory(session).size());
            counts(session, 0, 0);
        }
        assertTrue(CapacityReservation.inventory(session).isEmpty());
    }

    private static Session lostCreationReply(Session session, java.util.concurrent.atomic.AtomicInteger saves,
                                            int failAt) {
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    try {
                        final Object result = method.invoke(session, arguments);
                        if ("save".equals(method.getName()) && saves.incrementAndGet() == failAt) {
                            throw new RepositoryException("synthetic pending creation reply lost");
                        }
                        return result;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    private static Session failingSaves(Session session, java.util.concurrent.atomic.AtomicInteger failures) {
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())
                            && failures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                        throw new RepositoryException("injected cleanup save failure");
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    @Test
    void takingCapacityOwnsItsPendingIdentityBeforeAnUncertainSave() throws RepositoryException {
        final Session session = prepared();
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(RepositoryException.class, () -> CapacityLedger.take(
                lostCreationReply(session, saves, 1), CALLER, CHARGES, CONTRACT));
        assertTrue(CapacityReservation.inventory(session).isEmpty());
        counts(session, 0, 0);
    }

    @Test
    void replacementPublicationOwnsAnUncertainPendingIdentity() throws RepositoryException {
        final Session session = prepared();
        ArtifactStore.prepare(session, CALLER);
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, CALLER);
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, CALLER);
        final StatePath operation = StatePath.deployment("synthetic-operation");
        final Node record = session.getNode(StatePath.ROOT).addNode("synthetic-operation", "nt:unstructured");
        session.save();
        final List<CapacityReservation.Charge> promised = List.of(
                new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_BYTES, 9),
                new CapacityReservation.Charge(AccountedQuantity.OPERATION_RESERVATION_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.OPERATION_RESERVATION_BYTES, 9));
        final CapacityReservation source = CapacityReservation.create(session, OWNER, CALLER, promised)
                .orElseThrow();
        assertInstanceOf(CapacityLedger.Admitted.class, CapacityLedger.reserve(session, source, CONTRACT));
        final Node declaration = record.addNode("promise", "nt:unstructured");
        CapacityReservation.retain(session, source, declaration);
        session.save();
        final var content = new java.io.ByteArrayInputStream("synthetic".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        final var saves = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(RepositoryException.class, () -> ArtifactStore.publishReserved(
                lostCreationReply(session, saves, 1), CALLER, operation,
                new ArtifactStore.Publication(new ArtifactSlot("synthetic-slot"), 9, content), 1,
                CONTRACT, new ArtifactStore.Prepaid(operation.child("promise"),
                        rs.slingshot.agent.digest.Digest.of("synthetic".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)))));
        assertEquals(List.of(source), CapacityReservation.inventory(session));
        assertEquals(9, content.available());
        assertFalse(session.nodeExists(new ArtifactSlot("synthetic-slot").under(operation).path()));
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        assertEquals(9, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT));
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, CONTRACT));
        assertEquals(9, CapacityLedger.held(session, AccountedQuantity.OPERATION_RESERVATION_BYTES,
                CONTRACT));
    }

    private static void counts(Session session, long rows, long bytes) throws RepositoryException {
        assertEquals(rows, CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT));
        assertEquals(rows, CapacityLedger.heldBy(session, AccountedQuantity.EVENT_ROWS, CALLER, CONTRACT));
        assertEquals(bytes, CapacityLedger.held(session, AccountedQuantity.EVENT_BYTES, CONTRACT));
        assertEquals(bytes, CapacityLedger.heldBy(session, AccountedQuantity.EVENT_BYTES, CALLER, CONTRACT));
    }

    private Session prepared() throws RepositoryException {
        final Session session = session(sling.resourceResolver());
        if (!session.nodeExists("/var")) {
            session.getRootNode().addNode("var", "nt:unstructured");
        }
        session.getNode("/var").addNode("slingshot-agent", "nt:unstructured");
        session.save();
        LedgerAdmission.prepare(session, CALLER);
        return session;
    }

    private ResourceResolver anotherResolver() throws LoginException {
        return Objects.requireNonNull(sling.getService(ResourceResolverFactory.class))
                .getResourceResolver(Map.of());
    }

    private static Session session(ResourceResolver resolver) {
        return Objects.requireNonNull(resolver.adaptTo(Session.class));
    }

}
