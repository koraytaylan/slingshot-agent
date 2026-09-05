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
import java.util.ArrayList;
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

/** Identity retries and uncertain commit responses on independently refreshed Oak sessions. */
@ExtendWith(SlingContextExtension.class)
final class CapacityReservationTest {

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
    void pendingIdentitySurvivesItsCreatingSessionWithoutCharging() throws RepositoryException,
            LoginException {
        prepared();
        final StatePath path;
        try (ResourceResolver resolver = anotherResolver()) {
            path = CapacityReservation.create(session(resolver), OWNER, CALLER, CHARGES).orElseThrow().path();
        }
        try (ResourceResolver resolver = anotherResolver()) {
            final Session recovered = session(resolver);
            final CapacityReservation reservation = CapacityReservation.read(recovered, path).orElseThrow();
            assertEquals(OWNER, reservation.owner());
            counts(recovered, 0, 0);
            CapacityLedger.release(recovered, reservation, CONTRACT);
            assertFalse(recovered.nodeExists(path.path()));
            counts(recovered, 0, 0);
        }
    }

    @Test
    void lostAdmissionResponseRetriesTheSameVectorOnlyOnce() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.reserve(lostReply(session), reservation, CONTRACT));
        assertInstanceOf(CapacityLedger.Admitted.class,
                CapacityLedger.reserve(session, reservation, CONTRACT));
        counts(session, 1, 23);
    }

    @Test
    void ownerRecoveryPreservesLegacyChargesUntilTheirResourcesAreRetired()
            throws RepositoryException, LoginException {
        final Session session = prepared();
        LegacyCapacity.seed(session, AccountedQuantity.EVENT_ROWS, CALLER, 1);
        LegacyCapacity.seed(session, AccountedQuantity.EVENT_BYTES, CALLER, 23);
        final Node legacy = session.getNode(StatePath.ROOT).addNode("legacy-retained", "nt:unstructured");
        legacy.setProperty("size", 23);
        session.save();
        final CapacityReservation abandoned = create(session);
        CapacityLedger.reserve(session, abandoned, CONTRACT);
        final CapacityReservation live = CapacityReservation.create(session,
                CapacityReservation.processOwner(), CALLER, CHARGES).orElseThrow();
        CapacityLedger.reserve(session, live, CONTRACT);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session recovery = session(resolver);
            CapacityLedger.recoverOwner(recovery, OWNER, CONTRACT);
            CapacityLedger.recoverOwner(recovery, OWNER, CONTRACT);
            counts(recovery, 2, 46);
            assertTrue(recovery.nodeExists(legacy.getPath()));
            assertTrue(recovery.nodeExists(live.path().path()));
            assertFalse(recovery.nodeExists(abandoned.path().path()));
            final Node retained = recovery.getNode(legacy.getPath());
            final CapacityLedger.ResourceCharge charge = new CapacityLedger.ResourceCharge(
                    AccountedQuantity.EVENT_ROWS, AccountedQuantity.EVENT_BYTES, "size");
            CapacityLedger.releaseResource(recovery, retained, CALLER, charge, CONTRACT);
            CapacityLedger.releaseResource(recovery, retained, CALLER, charge, CONTRACT);
            counts(recovery, 1, 23);
            CapacityLedger.release(recovery, live, CONTRACT);
            counts(recovery, 0, 0);
        }
    }

    @Test
    void lostReleaseResponseCannotDebitAnotherIdenticalReservation() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation first = create(session);
        final CapacityReservation second = create(session);
        CapacityLedger.reserve(session, first, CONTRACT);
        CapacityLedger.reserve(session, second, CONTRACT);
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.release(lostReply(session), first, CONTRACT));
        CapacityLedger.release(session, first, CONTRACT);
        counts(session, 1, 23);
        assertInstanceOf(CapacityLedger.NotCounted.class,
                CapacityLedger.reserve(session, first, CONTRACT));
        counts(session, 1, 23);
        CapacityLedger.release(session, second, CONTRACT);
        counts(session, 0, 0);
    }

    @Test
    void twoSessionsReleasingOneIdentityDebitOnlyOnce() throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        CapacityLedger.reserve(session, reservation, CONTRACT);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = session(resolver);
            CapacityLedger.release(SaveInterleaving.before(session,
                    () -> CapacityLedger.release(second, reservation, CONTRACT)), reservation, CONTRACT);
            counts(second, 0, 0);
        }
    }

    @Test
    void competingAdmissionOfOneIdentityChargesOnlyOnce() throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = session(resolver);
            assertInstanceOf(CapacityLedger.Admitted.class,
                    CapacityLedger.reserve(SaveInterleaving.before(session, () -> assertInstanceOf(
                            CapacityLedger.Admitted.class,
                            CapacityLedger.reserve(second, reservation, CONTRACT))), reservation, CONTRACT));
            counts(second, 1, 23);
        }
    }

    @Test
    void cancellingPendingIdentityFencesAnAdmissionAlreadyPrepared()
            throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = session(resolver);
            assertInstanceOf(CapacityLedger.NotCounted.class,
                    CapacityLedger.reserve(SaveInterleaving.before(session,
                            () -> CapacityLedger.release(second, reservation, CONTRACT)),
                            reservation, CONTRACT));
            counts(second, 0, 0);
        }
    }

    @Test
    void refusingTheSecondQuantityCommitsNoneOfTheVector() throws RepositoryException {
        final Session session = prepared();
        final long rows = AccountedQuantity.EVENT_ROWS.admissibleCallerShare(CONTRACT);
        CapacityLedger.take(session, CALLER,
                List.of(new CapacityReservation.Charge(AccountedQuantity.EVENT_ROWS, rows)), CONTRACT);
        final CapacityReservation reservation = create(session);
        assertInstanceOf(CapacityLedger.Refused.class,
                CapacityLedger.reserve(session, reservation, CONTRACT));
        counts(session, rows, 0);
        CapacityLedger.release(session, reservation, CONTRACT);
        counts(session, rows, 0);
    }

    @Test
    void persistedActiveIdentityCanBeReleasedAfterItsOwnerSessionDisappears()
            throws RepositoryException, LoginException {
        prepared();
        final StatePath path;
        try (ResourceResolver resolver = anotherResolver()) {
            final Session session = session(resolver);
            final CapacityReservation reservation = create(session);
            path = reservation.path();
            CapacityLedger.reserve(session, reservation, CONTRACT);
        }
        try (ResourceResolver resolver = anotherResolver()) {
            final Session recovered = session(resolver);
            final CapacityReservation reservation = CapacityReservation.read(recovered, path).orElseThrow();
            assertEquals(OWNER, reservation.owner());
            counts(recovered, 1, 23);
            CapacityLedger.release(recovered, reservation, CONTRACT);
            CapacityLedger.release(recovered, reservation, CONTRACT);
            counts(recovered, 0, 0);
        }
    }

    @Test
    void anotherChargeVectorCannotReuseAnExistingIdentity() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        final CapacityReservation different = new CapacityReservation(reservation.identifier(), OWNER,
                CALLER, List.of(new CapacityReservation.Charge(AccountedQuantity.EVENT_ROWS, 2)));
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.reserve(session, different, CONTRACT));
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.release(session, different, CONTRACT));
        assertFalse(session.hasPendingChanges());
        counts(session, 0, 0);
    }

    @Test
    void invalidOrMutableChargeVectorsCannotChangeAnIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CapacityReservation(UUID.randomUUID(), OWNER, CALLER, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapacityReservation(UUID.randomUUID(),
                OWNER, CALLER, List.of(CHARGES.getFirst(), CHARGES.getFirst())));
        final List<CapacityReservation.Charge> source = new ArrayList<>(CHARGES);
        final CapacityReservation reservation =
                new CapacityReservation(UUID.randomUUID(), OWNER, CALLER, source);
        source.clear();
        assertEquals(2, reservation.charges().size());
        assertThrows(UnsupportedOperationException.class,
                () -> reservation.charges().clear());
    }

    @Test
    void contendedCreationCannotLeavePartOfAPendingIdentity() throws RepositoryException {
        final Session session = prepared();
        final Session contended = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        throw new InvalidItemStateException("the other pending creation won");
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        assertTrue(CapacityReservation.create(contended, OWNER, CALLER, CHARGES).isEmpty());
        assertFalse(session.hasPendingChanges());
        assertFalse(session.nodeExists(StatePath.deployment(StatePath.CAPACITY)
                .child(CapacityReservation.NODE).path()));
        counts(session, 0, 0);
    }

    @Test
    void uncertainPublicationKeepsItsChargeWhenTheRequestIsCancelled() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        CapacityLedger.reserve(session, reservation, CONTRACT);
        final Node resource = session.getNode(StatePath.ROOT).addNode("retained-event", "nt:unstructured");
        CapacityReservation.retain(session, reservation, resource);
        assertThrows(RepositoryException.class, () -> lostReply(session).save());
        CapacityLedger.cancel(session, reservation, CONTRACT);
        CapacityLedger.cancel(session, reservation, CONTRACT);
        assertEquals(reservation.identifier().toString(), session.getNode(StatePath.ROOT + "/retained-event")
                .getProperty(CapacityReservation.RESOURCE_RESERVATION).getString());
        counts(session, 1, 23);
    }

    @Test
    void aCancelledIdentityCannotPublishAnAlreadyPreparedResource()
            throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        CapacityLedger.reserve(session, reservation, CONTRACT);
        final Node resource = session.getNode(StatePath.ROOT).addNode("cancelled-event", "nt:unstructured");
        CapacityReservation.retain(session, reservation, resource);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = session(resolver);
            assertThrows(InvalidItemStateException.class, () -> SaveInterleaving.before(session,
                    () -> CapacityLedger.cancel(second, reservation, CONTRACT)).save());
            session.refresh(false);
            assertFalse(second.nodeExists(StatePath.ROOT + "/cancelled-event"));
            counts(second, 0, 0);
        }
    }

    @Test
    void abandonedRetainedMetadataIsReleasedWhenItsDataHasGone() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        CapacityLedger.reserve(session, reservation, CONTRACT);
        final Node resource = session.getNode(StatePath.ROOT).addNode("removed-event", "nt:unstructured");
        CapacityReservation.retain(session, reservation, resource);
        session.save();
        resource.remove();
        session.save();
        CapacityLedger.cancel(session, reservation, CONTRACT);
        counts(session, 0, 0);
    }

    @Test
    void repeatedResourceReleaseAndCancellationLeaveOtherWorkCounted() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation first = create(session);
        final CapacityReservation second = create(session);
        CapacityLedger.reserve(session, first, CONTRACT);
        CapacityLedger.reserve(session, second, CONTRACT);
        final Node resource = session.getNode(StatePath.ROOT).addNode("retired-event", "nt:unstructured");
        CapacityReservation.retain(session, first, resource);
        session.save();
        final CapacityLedger.ResourceCharge legacy = new CapacityLedger.ResourceCharge(
                AccountedQuantity.EVENT_ROWS, AccountedQuantity.EVENT_BYTES, "absent-size");
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.releaseResource(lostReply(session), resource, CALLER, legacy, CONTRACT));
        CapacityLedger.releaseResource(session, resource, CALLER, legacy, CONTRACT);
        CapacityLedger.cancel(session, first, CONTRACT);
        counts(session, 1, 23);
        CapacityLedger.release(session, second, CONTRACT);
        counts(session, 0, 0);
    }

    @Test
    void legacyResourceReleaseMarksItsIdentityWithBothCounterChanges() throws RepositoryException {
        final Session session = prepared();
        LegacyCapacity.seed(session, AccountedQuantity.EVENT_ROWS, CALLER, 2);
        LegacyCapacity.seed(session, AccountedQuantity.EVENT_BYTES, CALLER, 46);
        final Node resource = session.getNode(StatePath.ROOT).addNode("legacy-event", "nt:unstructured");
        resource.setProperty("size", 23);
        session.save();
        final CapacityLedger.ResourceCharge legacy = new CapacityLedger.ResourceCharge(
                AccountedQuantity.EVENT_ROWS, AccountedQuantity.EVENT_BYTES, "size");
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.releaseResource(lostReply(session), resource, CALLER, legacy, CONTRACT));
        CapacityLedger.releaseResource(session, resource, CALLER, legacy, CONTRACT);
        counts(session, 1, 23);
    }

    @Test
    void foreignResourcesCannotBeMarkedOrReleasedByTheCapacityAuthority() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        CapacityLedger.reserve(session, reservation, CONTRACT);
        final Node foreign = session.getRootNode().addNode("foreign-resource", "nt:unstructured");
        session.save();
        assertThrows(RepositoryException.class,
                () -> CapacityReservation.retain(session, reservation, foreign));
        assertThrows(RepositoryException.class, () -> CapacityLedger.releaseResource(session, foreign, CALLER,
                new CapacityLedger.ResourceCharge(AccountedQuantity.EVENT_ROWS,
                        AccountedQuantity.EVENT_BYTES, "size"), CONTRACT));
        assertFalse(foreign.hasProperty(CapacityReservation.RESOURCE_RESERVATION));
        assertFalse(session.hasPendingChanges());
        counts(session, 1, 23);
    }

    @Test
    void malformedPersistedChargesCannotChangeEitherCounter() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        session.getNode(reservation.path().path()).setProperty("amount_event_bytes", -1);
        session.save();
        assertThrows(RepositoryException.class, () -> CapacityReservation.read(session, reservation.path()));
        assertThrows(RepositoryException.class, () -> CapacityLedger.reserve(session, reservation, CONTRACT));
        assertFalse(session.hasPendingChanges());
        counts(session, 0, 0);
    }

    @Test
    void manifestRefusalLeavesEveryIdentityPending() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation first = create(session);
        final CapacityReservation second = CapacityReservation.create(session, OWNER, CALLER,
                List.of(new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES,
                        AccountedQuantity.EVENT_BYTES.admissibleTotal(CONTRACT)))).orElseThrow();
        assertInstanceOf(CapacityLedger.Refused.class,
                CapacityLedger.reserve(session, List.of(first, second), CONTRACT));
        counts(session, 0, 0);
        assertFalse(session.getNode(first.path().path())
                .getProperty(CapacityReservation.ACTIVE).getBoolean());
        assertFalse(session.getNode(second.path().path())
                .getProperty(CapacityReservation.ACTIVE).getBoolean());
    }

    @Test
    void manifestResponseLossRetriesWithoutDoubleCharging() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation first = create(session);
        final CapacityReservation second = create(session);
        final List<CapacityReservation> manifest = List.of(first, second);
        assertThrows(RepositoryException.class, () -> CapacityLedger.reserve(lostReply(session),
                manifest, CONTRACT));
        assertInstanceOf(CapacityLedger.Admitted.class, CapacityLedger.reserve(session, manifest, CONTRACT));
        counts(session, 2, 46);
        CapacityLedger.release(session, first, CONTRACT);
        counts(session, 1, 23);
        CapacityLedger.release(session, second, CONTRACT);
        counts(session, 0, 0);
    }

    @Test
    void cancelledManifestMemberFencesTheWholeActivation() throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation first = create(session);
        final CapacityReservation second = create(session);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session competing = session(resolver);
            final CapacityLedger.NotCounted outcome = assertInstanceOf(CapacityLedger.NotCounted.class,
                    CapacityLedger.reserve(SaveInterleaving.before(session,
                            () -> CapacityLedger.release(competing, second, CONTRACT)),
                            List.of(first, second), CONTRACT));
            assertEquals(WriteOutcome.VALUE_CHANGED, outcome.outcome());
            counts(competing, 0, 0);
        }
        assertFalse(session.getNode(first.path().path())
                .getProperty(CapacityReservation.ACTIVE).getBoolean());
    }

    @Test
    void manifestRequiresNonemptyDistinctIdentities() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        assertThrows(IllegalArgumentException.class,
                () -> CapacityLedger.reserve(session, List.of(), CONTRACT));
        assertThrows(IllegalArgumentException.class,
                () -> CapacityLedger.reserve(session, List.of(reservation, reservation), CONTRACT));
        counts(session, 0, 0);
    }

    @Test
    void transferredCapacitySurvivesLostReplyAndDelayedSourceRelease() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation original = retainedSource(session);
        final CapacityReservation replacement = create(session);
        final Node destination = session.getNode(StatePath.ROOT).addNode("destination", "nt:unstructured");
        assertInstanceOf(CapacityLedger.Admitted.class, CapacityLedger.transfer(session,
                session.getNode(StatePath.ROOT + "/source"), destination, replacement, CONTRACT));
        assertThrows(RepositoryException.class, () -> lostReply(session).save());
        CapacityLedger.cancel(session, replacement, CONTRACT);
        CapacityLedger.release(session, original, CONTRACT);
        counts(session, 1, 23);
        assertEquals(replacement, CapacityReservation.ofResource(session,
                session.getNode(StatePath.ROOT + "/destination")).orElseThrow());
        assertTrue(CapacityReservation.ofResource(session,
                session.getNode(StatePath.ROOT + "/source")).isEmpty());
    }

    @Test
    void transferRefusalPreservesTheWholeOriginalVector() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation original = retainedSource(session);
        final CapacityReservation replacement = CapacityReservation.create(session, OWNER, CALLER,
                List.of(new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES,
                        AccountedQuantity.EVENT_BYTES.admissibleTotal(CONTRACT) + 1))).orElseThrow();
        try (CapacityReservation.Guard guard = replacement.guard(session, CONTRACT)) {
            final Node destination = session.getNode(StatePath.ROOT)
                    .addNode("destination", "nt:unstructured");
            assertInstanceOf(CapacityLedger.Refused.class, CapacityLedger.transfer(session,
                    session.getNode(StatePath.ROOT + "/source"), destination, guard.reservation(), CONTRACT));
        }
        counts(session, 1, 23);
        assertFalse(session.nodeExists(StatePath.ROOT + "/destination"));
        assertEquals(original, CapacityReservation.ofResource(session,
                session.getNode(StatePath.ROOT + "/source")).orElseThrow());
    }

    @Test
    void sourceRetirementFencesAPreparedTransfer() throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation original = retainedSource(session);
        final CapacityReservation replacement = create(session);
        try (ResourceResolver resolver = anotherResolver();
             CapacityReservation.Guard guard = replacement.guard(session, CONTRACT)) {
            final Node destination = session.getNode(StatePath.ROOT)
                    .addNode("destination", "nt:unstructured");
            assertInstanceOf(CapacityLedger.Admitted.class, CapacityLedger.transfer(session,
                    session.getNode(StatePath.ROOT + "/source"), destination, guard.reservation(), CONTRACT));
            assertThrows(InvalidItemStateException.class, () -> SaveInterleaving.before(session,
                    () -> CapacityLedger.release(session(resolver), original, CONTRACT)).save());
        }
        counts(session, 0, 0);
        assertFalse(session.nodeExists(StatePath.ROOT + "/destination"));
    }

    @Test
    void transferRejectsAnActiveReplacementWithoutReleasingTheSource() throws RepositoryException {
        final Session session = prepared();
        retainedSource(session);
        final CapacityReservation replacement = create(session);
        CapacityLedger.reserve(session, replacement, CONTRACT);
        final Node destination = session.getNode(StatePath.ROOT).addNode("destination", "nt:unstructured");
        assertThrows(RepositoryException.class, () -> CapacityLedger.transfer(session,
                session.getNode(StatePath.ROOT + "/source"), destination, replacement, CONTRACT));
        session.refresh(false);
        counts(session, 2, 46);
    }

    @Test
    void deletingTheSourceResourceFencesItsPreparedTransfer() throws RepositoryException, LoginException {
        final Session session = prepared();
        final CapacityReservation original = retainedSource(session);
        final CapacityReservation replacement = create(session);
        try (ResourceResolver resolver = anotherResolver();
             CapacityReservation.Guard guard = replacement.guard(session, CONTRACT)) {
            final Node destination = session.getNode(StatePath.ROOT)
                    .addNode("destination", "nt:unstructured");
            assertInstanceOf(CapacityLedger.Admitted.class, CapacityLedger.transfer(session,
                    session.getNode(StatePath.ROOT + "/source"), destination, guard.reservation(), CONTRACT));
            assertThrows(InvalidItemStateException.class, () -> SaveInterleaving.before(session, () -> {
                final Session competing = session(resolver);
                competing.getNode(StatePath.ROOT + "/source").remove();
                competing.save();
            }).save());
        }
        CapacityLedger.cancel(session, original, CONTRACT);
        counts(session, 0, 0);
        assertFalse(session.nodeExists(StatePath.ROOT + "/destination"));
    }

    @Test
    void batchCleanupPreservesPublishedSlotsAndCancelsUnpublishedSlots() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation.Batch batch = CapacityReservation.batch(session, CONTRACT);
        try (batch) {
            final CapacityReservation retained = batch.create(CALLER, CHARGES).orElseThrow();
            final List<CapacityReservation> view = batch.reservations();
            batch.create(CALLER, CHARGES).orElseThrow();
            assertEquals(2, view.size());
            assertThrows(UnsupportedOperationException.class, view::clear);
            CapacityLedger.reserve(session, batch.reservations(), CONTRACT);
            final Node source = session.getNode(StatePath.ROOT).addNode("source", "nt:unstructured");
            CapacityReservation.retain(session, retained, source);
            session.save();
        }
        batch.close();
        counts(session, 1, 23);
        assertThrows(IllegalStateException.class, () -> batch.create(CALLER, CHARGES));
    }

    @Test
    void batchCleanupAttemptsEveryIdentityAfterOneCancellationFails() throws RepositoryException {
        final Session session = prepared();
        final var failures = new java.util.concurrent.atomic.AtomicInteger();
        final CapacityReservation.Batch batch = CapacityReservation.batch(failingSaves(session, failures),
                CONTRACT);
        try (batch) {
            batch.create(CALLER, CHARGES).orElseThrow();
            batch.create(CALLER, CHARGES).orElseThrow();
            CapacityLedger.reserve(session, batch.reservations(), CONTRACT);
            failures.set(1);
            assertThrows(RepositoryException.class, batch::close);
            counts(session, 1, 23);
            batch.close();
            counts(session, 0, 0);
        }
    }

    @Test
    void batchCleanupPreservesPrimaryAndEverySuppressedFailure() throws RepositoryException {
        final Session session = prepared();
        final var failures = new java.util.concurrent.atomic.AtomicInteger();
        final CapacityReservation.Batch batch = CapacityReservation.batch(failingSaves(session, failures),
                CONTRACT);
        final IllegalStateException primary = assertThrows(IllegalStateException.class, () -> {
            try (batch) {
                batch.create(CALLER, CHARGES).orElseThrow();
                batch.create(CALLER, CHARGES).orElseThrow();
                failures.set(2);
                throw new IllegalStateException("manifest preparation failed");
            }
        });
        assertEquals(1, primary.getSuppressed().length);
        assertEquals(1, primary.getSuppressed()[0].getSuppressed().length);
        batch.close();
        counts(session, 0, 0);
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
    void retiredOwnerRecoveryPreservesRetainedDataAndOtherOwners() throws RepositoryException {
        final Session session = prepared();
        create(session);
        CapacityLedger.reserve(session, create(session), CONTRACT);
        final CapacityReservation retained = retainedSource(session);
        CapacityLedger.take(session, CALLER, CHARGES, CONTRACT);
        CapacityLedger.recoverOwner(session, OWNER, CONTRACT);
        CapacityLedger.recoverOwner(session, OWNER, CONTRACT);
        counts(session, 2, 46);
        assertEquals(retained, CapacityReservation.ofResource(session,
                session.getNode(StatePath.ROOT + "/source")).orElseThrow());
        session.getNode(StatePath.ROOT + "/source").remove();
        session.save();
        CapacityLedger.recoverOwner(session, OWNER, CONTRACT);
        counts(session, 1, 23);
    }

    @Test
    void recoveryCannotTreatTheCurrentProcessAsRetired() throws RepositoryException {
        final Session session = prepared();
        CapacityLedger.take(session, CALLER, CHARGES, CONTRACT);
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.recoverOwner(session, CapacityReservation.processOwner(), CONTRACT));
        counts(session, 1, 23);
    }

    @Test
    void interruptedRecoveryRetriesWithoutDebitingALiveOwner() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation abandoned = create(session);
        CapacityLedger.reserve(session, abandoned, CONTRACT);
        CapacityLedger.take(session, CALLER, CHARGES, CONTRACT);
        final Session lost = (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    try {
                        final Object returned = method.invoke(session, arguments);
                        if ("save".equals(method.getName()) && !session.nodeExists(abandoned.path().path())) {
                            throw new RepositoryException("the recovery commit reply was lost");
                        }
                        return returned;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        assertThrows(RepositoryException.class, () -> CapacityLedger.recoverOwner(lost, OWNER, CONTRACT));
        CapacityLedger.recoverOwner(session, OWNER, CONTRACT);
        counts(session, 1, 23);
    }

    @Test
    void recoveryRetriesDiscoveryAfterAConcurrentCapacityWrite() throws RepositoryException, LoginException {
        final Session session = prepared();
        CapacityLedger.reserve(session, create(session), CONTRACT);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session competing = session(resolver);
            CapacityLedger.recoverOwner(SaveInterleaving.before(session,
                    () -> CapacityLedger.take(competing, CALLER, CHARGES, CONTRACT)), OWNER, CONTRACT);
        }
        counts(session, 1, 23);
    }

    @Test
    void malformedInventoryPreventsPartialRecovery() throws RepositoryException {
        final Session session = prepared();
        CapacityLedger.reserve(session, create(session), CONTRACT);
        final CapacityReservation malformed = create(session);
        session.getNode(malformed.path().path()).setProperty("owner", "not-a-process-identity");
        session.save();
        assertThrows(RepositoryException.class, () -> CapacityLedger.recoverOwner(session, OWNER, CONTRACT));
        counts(session, 1, 23);
    }

    @Test
    void recoveryAcceptsAnEmptyPreparedInventory() throws RepositoryException {
        final Session session = prepared();
        CapacityLedger.recoverOwner(session, OWNER, CONTRACT);
        counts(session, 0, 0);
    }

    @Test
    void inventoryContentionExhaustionLeavesOwnedCapacityUntouched() throws RepositoryException {
        final Session session = prepared();
        CapacityLedger.reserve(session, create(session), CONTRACT);
        final Session contended = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        throw new InvalidItemStateException("inventory validation contended");
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.recoverOwner(contended, OWNER, CONTRACT));
        assertFalse(session.hasPendingChanges());
        counts(session, 1, 23);
    }

    @Test
    void misplacedIdentityCannotBeSilentlyIgnoredDuringRecovery() throws RepositoryException {
        final Session session = prepared();
        final CapacityReservation reservation = create(session);
        CapacityLedger.reserve(session, reservation, CONTRACT);
        final Node alternate = session.getNode(reservation.path().path()).getParent().getParent()
                .addNode("wrong-bucket", "nt:unstructured");
        session.move(reservation.path().path(), alternate.getPath() + "/" + reservation.identifier());
        session.save();
        assertThrows(RepositoryException.class, () -> CapacityLedger.recoverOwner(session, OWNER, CONTRACT));
        counts(session, 1, 23);
    }

    private static CapacityReservation retainedSource(Session session) throws RepositoryException {
        final CapacityReservation original = create(session);
        CapacityLedger.reserve(session, original, CONTRACT);
        final Node source = session.getNode(StatePath.ROOT).addNode("source", "nt:unstructured");
        CapacityReservation.retain(session, original, source);
        session.save();
        return original;
    }

    private static CapacityReservation create(Session session) throws RepositoryException {
        return CapacityReservation.create(session, OWNER, CALLER, CHARGES).orElseThrow();
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

    private static Session lostReply(Session session) {
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    try {
                        final Object returned = method.invoke(session, arguments);
                        if ("save".equals(method.getName())) {
                            throw new RepositoryException("the committed save reply was lost");
                        }
                        return returned;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }
}
