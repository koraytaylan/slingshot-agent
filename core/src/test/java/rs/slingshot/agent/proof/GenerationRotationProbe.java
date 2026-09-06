// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.SubmissionAdmission;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.GenerationRotation;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.SaveInterleaving;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/** Test-only publication and readback across a killed Sling process. */
public final class GenerationRotationProbe {

    private static final AgentContract CONTRACT = ((AgentContract.Loaded) AgentContract.load()).contract();
    private static final long NOW = 1000;
    private static final String INTERRUPTED = "stopped after the first rotation save";
    private static final String PAYLOAD = "retained generation answer";
    private static final ArtifactSlot SLOT = ((ArtifactSlot.Held) ArtifactSlot.of("retained-answer")).slot();

    private GenerationRotationProbe() {
    }

    /** Publishes real operation, snapshot, and artifact records in the original generation. */
    public static String prepare(Session session) throws RepositoryException, IOException {
        final var submission = ExclusiveTransitionProbe.submission("generation");
        require(SubmissionAdmission.admit(session, submission, NOW, CONTRACT)
                instanceof AdmissionOutcome.Accepted,
                "the original generation did not accept its operation");
        LedgerAdmission.prepare(session, submission.caller());
        ArtifactStore.prepare(session, submission.caller());
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(JobEvent.GENERATION, new DocumentValue.Whole(1));
        members.put(JobEvent.IDENTIFIER,
                new DocumentValue.Text(submission.identity().identifier().rendered()));
        members.put(JobEvent.KIND, new DocumentValue.Text("accepted"));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(0));
        final DocumentValue.Mapping document = new DocumentValue.Mapping(members);
        final JobEvent event = ((JobEvent.Held) JobEvent.read(document, submission.identity().generation(),
                CONTRACT)).event();
        final byte[] canonical = ((CanonicalByteWriter.Written) CanonicalByteWriter.write(document)).bytes();
        require(SnapshotStore.record(session, submission.caller(), event, canonical, NOW, CONTRACT)
                instanceof EventLedger.Appended, "the snapshot was not published");
        final byte[] payload = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        require(ArtifactStore.publish(session, submission.caller(),
                OperationStore.pathOf(submission.identity()),
                new ArtifactStore.Publication(SLOT, payload.length, new ByteArrayInputStream(payload)),
                NOW, CONTRACT) instanceof ArtifactStore.Published, "the artifact was not published");
        return "generation-prepared";
    }

    /** Stops the method immediately after its first real repository commit. */
    public static String interrupt(Session session) throws RepositoryException {
        final ClassLoader loader = Objects.requireNonNull(
                FrameworkUtil.getBundle(GenerationRotationProbe.class))
                .adapt(BundleWiring.class).getClassLoader();
        final Session interrupted = SaveInterleaving.before(session, () -> {
            session.save();
            throw new RepositoryException(INTERRUPTED);
        }, loader);
        try {
            GenerationRotation.rotate(interrupted,
                    ((EventStoreGeneration.Held) EventStoreGeneration.of(2)).generation(), NOW, CONTRACT);
            throw new IllegalStateException("rotation never reached its first save");
        } catch (final RepositoryException stopped) {
            if (!INTERRUPTED.equals(stopped.getMessage())) {
                throw stopped;
            }
            return "interrupted";
        }
    }

    /** Reads the persisted metadata and every retained record from the surviving node. */
    public static String view(Session session) throws RepositoryException, IOException {
        session.refresh(false);
        final var current = GenerationStore.serving(session);
        if (!(current instanceof final GenerationStore.Held held) || held.generation().number() != 2) {
            return "waiting";
        }
        require(GenerationStore.served(session).equals(List.of(1L, 2L)), "serving and history disagree");
        final var submission = ExclusiveTransitionProbe.submission("generation");
        final var access = GenerationRotation.accessTo(session, submission.identity().generation());
        require(access instanceof GenerationRotation.Readable, "the original generation was retired");
        final long expected = NOW
                + CONTRACT.value(ContractLimit.MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS)
                + CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        require(((GenerationRotation.Readable) access).generation()
                .retainedUntilUnixMilliseconds() == expected,
                "the generation retention deadline is incomplete");
        require(OperationStore.read(session, submission.identity()) instanceof OperationStore.Held,
                "the retained operation is unreadable");
        final var path = OperationStore.pathOf(submission.identity());
        final var snapshot = SnapshotStore.read(session, path);
        require(snapshot instanceof SnapshotStore.Known
                && ((SnapshotStore.Known) snapshot).snapshot().kind() == JobEventKind.ACCEPTED,
                "the retained snapshot is unreadable");
        try (var payload = ArtifactStore.open(session, path, SLOT).orElseThrow()) {
            require(PAYLOAD.equals(new String(payload.readAllBytes(), StandardCharsets.UTF_8)),
                    "the retained artifact bytes changed");
        }
        require(CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT) == 1,
                "the retained artifact row charge changed");
        require(CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT)
                == PAYLOAD.getBytes(StandardCharsets.UTF_8).length,
                "the retained artifact byte charge changed");
        return "retained";
    }

    private static void require(boolean holds, String failure) {
        if (!holds) {
            throw new IllegalStateException(failure);
        }
    }
}
