// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.SubmissionAdmission;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.CompareAndSet;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.SaveInterleaving;
import rs.slingshot.agent.store.StatePath;

/**
 * Test-only entry point for executing the built store classes in two real Sling runtimes.
 * Never included in either product bundle or any customer package.
 */
public final class ExclusiveTransitionProbe extends SlingAllMethodsServlet implements BundleActivator {

    private static final long serialVersionUID = 1L;
    private static final long NOW = 1000;
    private static final String COUNTER = StatePath.ROOT + "/proof-counter";
    private static final String EFFECTS = "/var/slingshot-proof/effects";
    private static final AgentContract CONTRACT = ((AgentContract.Loaded) AgentContract.load()).contract();
    private static final AccountedQuantity CAPACITY = AccountedQuantity.CONCURRENT_EVENT_STREAMS;
    private static final StatePath.Caller CAPACITY_CALLER =
            ((StatePath.Held) StatePath.caller("proof-capacity-owner")).caller();
    private static final List<CapacityReservation.Charge> CAPACITY_CHARGE =
            List.of(new CapacityReservation.Charge(CAPACITY, 1));
    private final transient Map<String, Barrier> barriers = new ConcurrentHashMap<>();

    /** Restores an empty set of test barriers if the servlet is deserialized. */
    private Object readResolve() {
        return new ExclusiveTransitionProbe();
    }

    /** Starts only the test servlet, with no product component descriptors. */
    @Override
    public void start(BundleContext context) {
        context.registerService(Servlet.class, this, FrameworkUtil.asDictionary(Map.of(
                "sling.servlet.paths", "/bin/slingshot-proof/transitions",
                "sling.servlet.methods", new String[] {"GET", "POST"})));
    }

    /** The framework unregisters the servlet when the test bundle stops. */
    @Override
    public void stop(BundleContext context) {
        barriers.values().forEach(barrier -> barrier.release().countDown());
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        final String fixture = request.getParameter("fixture");
        final Barrier barrier = fixture == null ? null : barriers.get(fixture);
        response.getWriter().write(barrier != null && barrier.reached().getCount() == 0
                ? "prepared" : "ready");
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        final String action = request.getParameter("action");
        final String fixture = request.getParameter("fixture");
        try {
            final Session session = Objects.requireNonNull(request.getResourceResolver()
                    .adaptTo(Session.class));
            response.getWriter().write(execute(session, action, fixture));
        } catch (final RepositoryException failed) {
            response.setStatus(500);
            response.getWriter().write(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    private String execute(Session session, String action, String fixture)
            throws RepositoryException, IOException {
        return switch (action) {
            case "prepare" -> prepare(session);
            case "generation-prepare" -> GenerationRotationProbe.prepare(session);
            case "generation-view" -> GenerationRotationProbe.view(session);
            case "generation-lose" -> {
                final String outcome = GenerationRotationProbe.interrupt(session);
                await(fixture);
                yield outcome;
            }
            case "capacity-source" -> capacitySource(session);
            case "capacity-live" -> capacityLive(session);
            case "capacity-view" -> capacityView(session);
            case "capacity-recover" -> {
                CapacityLedger.recoverOwner(session, UUID.fromString(fixture), CONTRACT);
                yield "recovered";
            }
            case "view" -> view(session, fixture);
            case "arm" -> {
                barriers.put(fixture, new Barrier(new CountDownLatch(1), new CountDownLatch(1)));
                yield "armed";
            }
            case "release" -> {
                barriers.get(fixture).release().countDown();
                yield "released";
            }
            case "cas" -> CompareAndSet.set(paused(session, fixture),
                    StatePath.deployment("proof-counter"), "count", 0, 1).name();
            case "admit" -> SubmissionAdmission.admit(paused(session, fixture), submission(fixture),
                    NOW, CONTRACT).getClass().getSimpleName();
            case "start" -> startAndCount(session, fixture, true);
            case "lose" -> {
                final String outcome = startAndCount(session, fixture, false);
                await(fixture);
                yield outcome;
            }
            case "resend" -> {
                final AdmissionOutcome recognised = SubmissionAdmission.admit(session,
                        submission(fixture), NOW, CONTRACT);
                yield recognised.getClass().getSimpleName() + "/"
                        + startAndCount(session, fixture, false);
            }
            default -> throw new IllegalArgumentException("unknown test action: " + action);
        };
    }

    private static String capacitySource(Session session) throws RepositoryException {
        CapacityLedger.prepare(session, CAPACITY, CAPACITY_CALLER);
        CapacityReservation.create(session, CapacityReservation.processOwner(), CAPACITY_CALLER,
                CAPACITY_CHARGE).orElseThrow();
        capacityLive(session);
        final CapacityReservation retained = ((CapacityLedger.Reserved)
                CapacityLedger.take(session, CAPACITY_CALLER, CAPACITY_CHARGE, CONTRACT)).reservation();
        final Node data = session.getNode(StatePath.ROOT)
                .addNode("proof-retained-capacity", "nt:unstructured");
        data.setProperty("retained", true);
        CapacityReservation.retain(session, retained, data);
        session.save();
        return CapacityReservation.processOwner() + "," + retained.identifier();
    }

    private static String capacityLive(Session session) throws RepositoryException {
        final CapacityReservation reservation = ((CapacityLedger.Reserved)
                CapacityLedger.take(session, CAPACITY_CALLER, CAPACITY_CHARGE, CONTRACT)).reservation();
        return reservation.identifier().toString();
    }

    private static String capacityView(Session session) throws RepositoryException {
        session.refresh(false);
        if (!session.nodeExists(CapacityLedger.totalPath(CAPACITY).path())) {
            return "unprepared";
        }
        final long total = CapacityLedger.held(session, CAPACITY, CONTRACT);
        final long caller = CapacityLedger.heldBy(session, CAPACITY, CAPACITY_CALLER, CONTRACT);
        final long[] inventory = capacityInventory(session.getNode(StatePath.deployment(StatePath.CAPACITY)
                .child(CapacityReservation.NODE).path()), StatePath.BUCKET_DEPTH);
        return total + "/" + caller + "/" + inventory[0] + "/" + inventory[1];
    }

    private static long[] capacityInventory(Node parent, int depth) throws RepositoryException {
        final long[] totals = new long[2];
        final javax.jcr.NodeIterator children = parent.getNodes();
        while (children.hasNext()) {
            final Node child = children.nextNode();
            if (depth > 0) {
                final long[] nested = capacityInventory(child, depth - 1);
                totals[0] += nested[0];
                totals[1] += nested[1];
            } else if (child.getProperty("active").getBoolean()) {
                totals[0] += child.hasProperty("amount_concurrent_event_streams")
                        ? child.getProperty("amount_concurrent_event_streams").getLong() : 0;
            } else {
                totals[1] += 1;
            }
        }
        return totals;
    }

    private static String view(Session session, String fixture)
            throws RepositoryException, IOException {
        session.refresh(false);
        if ("counter".equals(fixture)) {
            return session.nodeExists(COUNTER)
                    ? Long.toString(session.getNode(COUNTER).getProperty("count").getLong())
                    : "unprepared";
        }
        final StatePath path = OperationStore.pathOf(submission(fixture).identity());
        if (!session.nodeExists(path.path().substring(0, path.path().lastIndexOf('/')))) {
            return "unprepared";
        }
        final OperationStore.Outcome outcome = OperationStore.read(session, submission(fixture).identity());
        return outcome instanceof final OperationStore.Held held
                ? held.operation().state().name() : "absent";
    }

    private String prepare(Session session) throws RepositoryException, IOException {
        walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        for (final String fixture : new String[] {"admission", "start", "loss"}) {
            final StatePath path = OperationStore.pathOf(submission(fixture).identity());
            walked(session, path.path().substring(0, path.path().lastIndexOf('/')));
            walked(session, EFFECTS + "/" + fixture);
            if (!"admission".equals(fixture)) {
                SubmissionAdmission.admit(session, submission(fixture), NOW, CONTRACT);
            }
        }
        walked(session, COUNTER);
        session.getNode(COUNTER).setProperty("count", 0);
        session.save();
        return "prepared";
    }

    private String startAndCount(Session session, String fixture, boolean pause)
            throws RepositoryException, IOException {
        final LogicalOperation operation = ((OperationStore.Held) OperationStore.read(session,
                submission(fixture).identity())).operation();
        final OperationStore.Outcome outcome = SubmissionAdmission.start(
                pause ? paused(session, fixture) : session, operation);
        if (outcome instanceof OperationStore.Held) {
            session.getNode(EFFECTS + "/" + fixture)
                    .addNode("effect-" + UUID.randomUUID(), "nt:unstructured");
            session.save();
        }
        return outcome.getClass().getSimpleName();
    }

    private Session paused(Session session, String fixture) {
        return SaveInterleaving.before(session, () -> await(fixture),
                Objects.requireNonNull(FrameworkUtil.getBundle(ExclusiveTransitionProbe.class))
                        .adapt(BundleWiring.class).getClassLoader());
    }

    private void await(String fixture) throws RepositoryException {
        final Barrier barrier = barriers.get(fixture);
        barrier.reached().countDown();
        try {
            if (!barrier.release().await(25, TimeUnit.SECONDS)) {
                throw new RepositoryException("test coordinator did not release " + fixture);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RepositoryException("test coordinator was interrupted", interrupted);
        }
    }

    static SubmissionAdmission.Submission submission(String fixture) throws IOException {
        final String original = resource("operation.json");
        final String identifier = Digest.of(fixture.getBytes(StandardCharsets.UTF_8)).rendered();
        final String rewritten = original.replace(
                "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8", identifier);
        final OperationIdentity identity = ((OperationIdentity.Held) OperationIdentity.of(
                document(rewritten), CONTRACT)).identity();
        final CommandContractIdentity command = ((CommandContractIdentity.Held)
                CommandContractIdentity.of(document(resource("command-contract.json")),
                        CommandContractIdentity.Bounds.from(CONTRACT))).identity();
        return new SubmissionAdmission.Submission(identity,
                Digest.of("identical submission".getBytes(StandardCharsets.UTF_8)), command,
                ((StatePath.Held) StatePath.caller("proof-caller")).caller(), NOW);
    }

    private static DocumentValue document(String source) {
        return ((BoundedDocumentReader.Read) BoundedDocumentReader.read(
                source.getBytes(StandardCharsets.UTF_8), BoundedDocumentReader.Bounds.from(CONTRACT)))
                .value();
    }

    private static String resource(String name) throws IOException {
        try (var stream = Objects.requireNonNull(ExclusiveTransitionProbe.class
                .getResourceAsStream("/fixtures/submission-admission/" + name))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String name : path.substring(1).split("/")) {
            node = node.hasNode(name) ? node.getNode(name) : node.addNode(name, "nt:unstructured");
        }
        session.save();
    }

    private record Barrier(CountDownLatch reached, CountDownLatch release) {
    }
}
