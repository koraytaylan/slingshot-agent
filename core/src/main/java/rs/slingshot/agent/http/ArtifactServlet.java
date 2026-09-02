// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.ArtifactRecord;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.stream.DefaultStreamTicker;
import rs.slingshot.agent.stream.StreamTicker;

/**
 * A result too large to answer inline, served so a reader can verify it rather than trust it.
 *
 * <p>The byte count and the digest travel with the bytes, in the head, before the body begins. A
 * reader that receives a short body knows it was short, and a reader that receives the whole body
 * can decide for itself whether it is the artifact this side recorded — which is a different and
 * stronger thing than this side saying so.</p>
 *
 * <p>Addressed by operation and slot, never by a repository path, and no answer discloses one. An
 * artifact belonging to somebody else's operation is answered exactly as one nobody has: a caller
 * who could tell those apart could ask which operations exist.</p>
 *
 * <p>An artifact whose stored bytes do not digest to what the record says is refused with no body
 * byte written at all. Sending bytes and then reporting a mismatch would leave a reader holding
 * something it has to decide what to do with, and the honest answer is that this side has nothing
 * it can vouch for.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/artifact"
})
public final class ArtifactServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "artifact-transfer";

    /** The query member naming which operation the artifact belongs to. */
    public static final String OPERATION_QUERY_MEMBER =
            OperationLookupServlet.OPERATION_QUERY_MEMBER;

    /** The query member naming which slot it sits in. */
    public static final String SLOT_QUERY_MEMBER = ArtifactIntakeServlet.SLOT_QUERY_MEMBER;

    /** The header the recorded byte count travels in, ahead of the bytes. */
    public static final String BYTE_COUNT_HEADER = "X-Slingshot-Artifact-Byte-Count";

    /** The header the recorded digest travels in, ahead of the bytes. */
    public static final String DIGEST_HEADER = "X-Slingshot-Artifact-Digest";

    /** What a request this build cannot read at all is answered with. */
    public static final int REFUSED = 400;

    /** What a slot nothing here holds, and one that is not this caller's, are both answered with. */
    public static final int NOTHING_HERE = 404;

    /** What an artifact nothing this operation produced references is answered with. */
    public static final int NOT_REFERENCED = 410;

    /** What an artifact whose own bytes do not match its record is answered with. */
    public static final int NOT_VOUCHED_FOR = 422;

    /** What an artifact this side is serving is answered with. */
    public static final int SERVED = 200;

    /** What a request is answered with when this build cannot read its own contract or store. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    private static final long serialVersionUID = 1L;

    /** What time it is to this servlet's transfers, and how they wait. */
    private final StreamTicker ticker;

    /** Holds a servlet running on this instance's own clock. */
    public ArtifactServlet() {
        this(new DefaultStreamTicker());
    }

    /**
     * Holds a servlet running on a clock somebody else keeps.
     *
     * <p>The seam a suite needs: an idle bound and a total bound are proved by advancing time
     * rather than by waiting through them, and a suite that waited would be proving what one
     * machine did one afternoon.</p>
     *
     * @param ticker what time it is to this servlet's transfers
     */
    public ArtifactServlet(StreamTicker ticker) {
        super();
        this.ticker = ticker;
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Which of the two rows on this path a request is for, decided by the request.
     *
     * @param request the request
     * @return the route's name
     */
    @Override
    protected String routeName(SlingHttpServletRequest request) {
        return INTAKE_METHOD.equals(request.getMethod())
                ? ArtifactIntakeServlet.ROUTE_NAME
                : ROUTE_NAME;
    }

    /** The method the other row on this path answers, which is bytes arriving rather than leaving. */
    private static final String INTAKE_METHOD = "POST";

    /**
     * Serves one artifact, or says why it did not.
     *
     * @param request the request, whose shape the base has already settled
     * @param response what to answer with
     * @throws IOException if the answer cannot be written
     * @throws javax.servlet.ServletException if the other row on this path cannot answer
     */
    @Override
    protected void serve(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException, javax.servlet.ServletException {
        if (INTAKE_METHOD.equals(request.getMethod())) {
            // The same path, the other row: bytes arriving for work that has not started. It is a
            // servlet of its own because it is a different decision, and it settles the request's
            // shape against its own row before it reads anything.
            new ArtifactIntakeServlet().service(request, response);
            return;
        }
        final AgentContract.Outcome loaded = AgentContract.load();
        if (!(loaded instanceof final AgentContract.Loaded held)) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        if (AuthenticationGate.refusalIn(AuthenticationGate.of(request)).isPresent()) {
            refuse(response, AuthenticationGate.STATUS);
            return;
        }
        try {
            asked(request, response, held.contract());
        } catch (final RepositoryException unreadable) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
        }
    }

    private void asked(SlingHttpServletRequest request, SlingHttpServletResponse response,
                       AgentContract contract) throws IOException, RepositoryException {
        final Optional<Session> store = sessionOf(request);
        if (store.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        final AgentOperationIdentifier.Outcome named = AgentOperationIdentifier.of(
                text(request.getParameter(OPERATION_QUERY_MEMBER)), contract);
        final ArtifactSlot.Outcome slot =
                ArtifactSlot.of(text(request.getParameter(SLOT_QUERY_MEMBER)));
        if (!(named instanceof final AgentOperationIdentifier.Held operation)
                || !(slot instanceof final ArtifactSlot.Held held)) {
            refuse(response, REFUSED);
            return;
        }
        held(response, store.get(), StatePath.operation(serving(store.get()),
                operation.identifier()), held.slot(), contract);
    }

    private void held(SlingHttpServletResponse response, Session store, StatePath operation,
                      ArtifactSlot slot, AgentContract contract)
            throws IOException, RepositoryException {
        final Optional<ArtifactRecord> record = ArtifactStore.read(store, operation, slot);
        if (record.isEmpty()) {
            // Nothing here holds it, or the caller's own session cannot see it: one answer, because
            // a caller who could tell those apart could ask which operations exist.
            refuse(response, NOTHING_HERE);
            return;
        }
        if (!referenced(store, operation, record.get())) {
            // Bytes nothing points at. An artifact a result does not reference is a leftover — from
            // an attempt that did not finish, or a payload that arrived for work that never ran —
            // and serving it would be this side vouching for something nothing here produced.
            refuse(response, NOT_REFERENCED);
            return;
        }
        vouched(response, store, operation, record.get(), contract);
    }

    private static boolean referenced(Session store, StatePath operation, ArtifactRecord record)
            throws RepositoryException {
        final javax.jcr.Node held = store.getNode(operation.path());
        return held.hasProperty(rs.slingshot.agent.execution.TerminalCommit.RESULT_SLOT)
                && held.getProperty(rs.slingshot.agent.execution.TerminalCommit.RESULT_SLOT)
                        .getString().equals(record.slot().name());
    }

    private void vouched(SlingHttpServletResponse response, Session store, StatePath operation,
                         ArtifactRecord record, AgentContract contract)
            throws IOException, RepositoryException {
        final Optional<DigestValue> held = digested(store, operation, record);
        if (held.isEmpty() || !held.get().equals(record.digest())) {
            // Not a byte of it goes out. A reader handed bytes and then told they may be the wrong
            // ones has been given something it has to decide what to do with.
            refuse(response, NOT_VOUCHED_FOR);
            return;
        }
        served(response, store, operation, record, contract);
    }

    private Optional<DigestValue> digested(Session store, StatePath operation,
                                           ArtifactRecord record) throws RepositoryException {
        final Optional<InputStream> bytes = ArtifactStore.open(store, operation, record.slot());
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream reading = bytes.get()) {
            return Optional.of(Digest.of(reading));
        } catch (final IOException unreadable) {
            // A store that cannot be read through is a store that has nothing to vouch for.
            return Optional.empty();
        }
    }

    private void served(SlingHttpServletResponse response, Session store, StatePath operation,
                        ArtifactRecord record, AgentContract contract)
            throws IOException, RepositoryException {
        final Optional<InputStream> bytes = ArtifactStore.open(store, operation, record.slot());
        if (bytes.isEmpty()) {
            refuse(response, NOTHING_HERE);
            return;
        }
        response.setStatus(SERVED);
        response.setContentType(route().mediaType());
        response.setHeader(BYTE_COUNT_HEADER, String.valueOf(record.byteCount()));
        response.setHeader(DIGEST_HEADER, headerSafe(record.digest().rendered()));
        try (InputStream reading = bytes.get(); OutputStream writing = response.getOutputStream()) {
            if (transfer(reading, writing, contract) < record.byteCount()) {
                // A transfer that stopped short has already told the reader everything this side
                // can: the count in the head is what should have arrived, and the body is what did.
                response.flushBuffer();
            }
        }
    }

    /**
     * Moves the bytes, ending a transfer that has stopped without ending one that is merely large.
     *
     * @param reading where the bytes come from
     * @param writing where they go
     * @param contract the authenticated contract, which declares both bounds
     * @return how many bytes moved
     * @throws IOException if either side fails
     */
    long transfer(InputStream reading, OutputStream writing, AgentContract contract)
            throws IOException {
        final byte[] buffer = new byte[Digest.READ_BUFFER_BYTES];
        final long startedAt = ticker.milliseconds();
        long lastMovedAt = startedAt;
        long moved = 0;
        int read = reading.read(buffer);
        while (read >= 0) {
            if (read > 0) {
                writing.write(buffer, 0, read);
                writing.flush();
                moved = moved + read;
                lastMovedAt = ticker.milliseconds();
            }
            if (!TransferDeadlines.isMoving(startedAt, lastMovedAt, ticker.milliseconds(),
                    contract)) {
                // It has stopped moving, or it has taken as long as a transfer may. Either way the
                // reader has the count it was promised in the head and can see what arrived.
                return moved;
            }
            read = reading.read(buffer);
        }
        return moved;
    }

    private static EventStoreGeneration serving(Session store) throws RepositoryException {
        final GenerationStore.Outcome held = GenerationStore.serving(store);
        return held instanceof final GenerationStore.Held serving
                ? serving.generation()
                : ((EventStoreGeneration.Held) EventStoreGeneration
                        .of(EventStoreGeneration.FIRST)).generation();
    }

    /**
     * One header value, holding only the characters a digest is written in.
     *
     * <p>The value comes out of the repository, and a repository somebody has written to directly
     * holds whatever they wrote. A line break in a header value is not a malformed header, it is a
     * second response — so what goes out is built here from an alphabet this build chose rather
     * than passed through from whatever was stored.</p>
     *
     * @param value what the store holds
     * @return the same value, with nothing in it that a header may not carry
     */
    private static String headerSafe(String value) {
        final StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index = index + 1) {
            final char character = value.charAt(index);
            if (character >= '0' && character <= '9' || character >= 'a' && character <= 'f') {
                safe.append(character);
            }
        }
        return safe.toString();
    }

    private static Optional<Session> sessionOf(SlingHttpServletRequest request) {
        return Optional.ofNullable(request.getResourceResolver().adaptTo(Session.class));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * The route this servlet answers, read from the committed table.
     *
     * @return the route
     */
    public static AgentRoute route() {
        final AgentRouteTable.Outcome outcome = AgentRouteTable.load();
        if (outcome instanceof final AgentRouteTable.Refused refused) {
            throw new IllegalStateException("no route table: " + refused.detail());
        }
        return ((AgentRouteTable.Loaded) outcome).table().route(ROUTE_NAME);
    }
}
