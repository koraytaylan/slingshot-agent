// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.http.IntakeSlotWrite;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.SaveInterleaving;

/** Test-only intake publication driven across the death of a real Sling writer. */
public final class IntakePublicationProbe {

    private static final AgentContract CONTRACT = ((AgentContract.Loaded) AgentContract.load()).contract();
    private static final ArtifactSlot SLOT = ((ArtifactSlot.Held) ArtifactSlot.of("payload")).slot();
    private static final long NOW = 1000;
    private static final String FIXTURE = "intake-crash";

    private IntakePublicationProbe() {
    }

    /** Admits a manifest with its complete reusable reservation before uploading. */
    public static String prepare(Session session) throws RepositoryException, IOException {
        final var submission = ExclusiveTransitionProbe.submission(FIXTURE);
        for (final AccountedQuantity quantity : quantities()) {
            CapacityLedger.prepare(session, quantity, submission.caller());
        }
        final var admission = IntakeSlotWrite.admit(session, submission,
                List.of(new IntakeSlotWrite.Declared(SLOT, payload().length, Digest.of(payload()))),
                NOW, CONTRACT);
        require(admission instanceof IntakeSlotWrite.Decided
                && ((IntakeSlotWrite.Decided) admission).outcome() instanceof AdmissionOutcome.Accepted,
                "the intake declaration was not admitted");
        return "intake-prepared";
    }

    /** Suspends before or after the real artifact-and-capacity save until the process is killed. */
    public static String paused(Session session, boolean committed, SaveInterleaving.Action pause)
            throws RepositoryException, IOException {
        final var path = SLOT.under(OperationStore.pathOf(
                ExclusiveTransitionProbe.submission(FIXTURE).identity()));
        final AtomicBoolean reached = new AtomicBoolean();
        final ClassLoader loader = Objects.requireNonNull(
                FrameworkUtil.getBundle(IntakePublicationProbe.class))
                .adapt(BundleWiring.class).getClassLoader();
        final Session watched = SaveInterleaving.beforeEverySave(session, () -> {
            if (session.nodeExists(path.path()) && reached.compareAndSet(false, true)) {
                if (committed) {
                    session.save();
                }
                pause.run();
                throw new RepositoryException("the coordinator released a writer that should have died");
            }
        }, loader);
        return write(watched);
    }

    /** Retries on the surviving runtime without special recovery of the intake promise. */
    public static String write(Session session) throws RepositoryException, IOException {
        final var submission = ExclusiveTransitionProbe.submission(FIXTURE);
        final var outcome = IntakeSlotWrite.write(session, submission.caller(),
                new IntakeSlotWrite.Arriving(OperationStore.pathOf(submission.identity()), SLOT,
                        new ByteArrayInputStream(payload()), NOW), CONTRACT);
        return outcome instanceof final IntakeSlotWrite.Refused refused
                ? refused.refusal().name() : outcome.getClass().getSimpleName();
    }

    /** Independently validates bytes, outstanding declarations, ownership and all four counters. */
    public static String view(Session session) throws RepositoryException, IOException {
        session.refresh(false);
        final var submission = ExclusiveTransitionProbe.submission(FIXTURE);
        final var operation = OperationStore.pathOf(submission.identity());
        if (!session.nodeExists(operation.path())) {
            return "waiting";
        }
        final var artifact = ArtifactStore.read(session, operation, SLOT);
        final boolean completed = artifact.isPresent();
        require(IntakeSlotWrite.outstanding(session, operation) == (completed ? 0 : 1),
                "the intake declaration and published artifact disagree");
        for (final AccountedQuantity quantity : quantities()) {
            final boolean promised = quantity == AccountedQuantity.OPERATION_RESERVATION_ROWS
                    || quantity == AccountedQuantity.OPERATION_RESERVATION_BYTES;
            final boolean bytes = quantity == AccountedQuantity.ARTIFACT_BYTES
                    || quantity == AccountedQuantity.OPERATION_RESERVATION_BYTES;
            final long expected = (promised && completed ? 0 : 1) * (bytes ? payload().length : 1);
            require(CapacityLedger.held(session, quantity, CONTRACT) == expected
                    && CapacityLedger.heldBy(session, quantity, submission.caller(), CONTRACT) == expected,
                    "intake capacity does not match its durable owner: " + quantity);
        }
        final var resource = session.getNode(completed ? SLOT.under(operation).path()
                : operation.child(IntakeSlotWrite.NODE).child(SLOT.name()).path());
        require(CapacityReservation.ofResource(session, resource).isPresent(), "the resource lost its owner");
        if (completed) {
            require(artifact.orElseThrow().digest().matches(Digest.of(payload())), "the digest changed");
            require(artifact.orElseThrow().byteCount() == payload().length, "the length changed");
            try (var content = ArtifactStore.open(session, operation, SLOT).orElseThrow()) {
                require(Arrays.equals(payload(), content.readAllBytes()), "the published bytes changed");
            }
        }
        return completed ? "complete" : "reserved";
    }

    private static List<AccountedQuantity> quantities() {
        return List.of(AccountedQuantity.ARTIFACT_ROWS, AccountedQuantity.ARTIFACT_BYTES,
                AccountedQuantity.OPERATION_RESERVATION_ROWS, AccountedQuantity.OPERATION_RESERVATION_BYTES);
    }

    private static byte[] payload() {
        return "the complete declared intake payload".getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean holds, String failure) {
        if (!holds) {
            throw new IllegalStateException(failure);
        }
    }
}
