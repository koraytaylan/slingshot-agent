// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.ServletException;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.TerminalCommit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.stream.StreamTicker;

/**
 * A result too large to answer inline, and the three ways it is refused.
 *
 * <p>The point of the headers is that a reader never has to believe this side. The count and the
 * digest arrive before the body, so a short body is detectable and a whole one is checkable — and
 * this suite checks it the way a reader would, by digesting what it received rather than by asking
 * the store what it holds.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ArtifactServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/artifact-transfer");

    private static final AgentContract CONTRACT = contract();

    /** The slot a result is published into. */
    private static final String SLOT = "result";

    /** An operation identifier this build reads, which is the one these artifacts belong to. */
    private static final String OPERATION =
            "9d1f2b6a37c0c4c3ab3f1d1e5b0a7c4f6d8e2a91b3c5d7e9f0a1b2c3d4e5f607";

    /** An identifier this build reads that nothing here holds. */
    private static final String ELSEWHERE =
            "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809";

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a small and a large artifact both transfer byte for byte, and verify themselves")
    void asmallAndAlargeArtifactBothTransferByteForByte() throws RepositoryException, IOException,
            ServletException {
        for (final String fixture : List.of("small.txt", "large.txt")) {
            published(fixture);
            final MockSlingHttpServletResponse served = ask(OPERATION, SLOT);
            assertEquals(ArtifactServlet.SERVED, served.getStatus(), served.getOutputAsString());
            assertArrayEquals(bytes(FIXTURES.resolve(fixture)), served.getOutput(),
                    fixture + " did not come back byte for byte");
            assertEquals(String.valueOf(bytes(FIXTURES.resolve(fixture)).length),
                    served.getHeader(ArtifactServlet.BYTE_COUNT_HEADER),
                    "the count in the head is not the count of the bytes");
            assertEquals(Digest.of(bytes(FIXTURES.resolve(fixture))).rendered(),
                    served.getHeader(ArtifactServlet.DIGEST_HEADER),
                    "a reader digesting what it received would disagree with the head");
        }
        assertTrue(bytes(FIXTURES.resolve("large.txt")).length > Digest.READ_BUFFER_BYTES,
                "the large fixture fits in one buffer, so nothing above crossed one");
    }

    @Test
    @DisplayName("an unknown slot, an unreferenced artifact, and a bad digest are three answers")
    void thethreeRefusalsAreDistinct() throws RepositoryException, IOException, ServletException {
        final Session session = published("small.txt");
        assertEquals(ArtifactServlet.NOTHING_HERE, ask(OPERATION, "a-slot-nobody-filled")
                        .getStatus(),
                "a slot nothing holds was served");
        final Node record = session.getNode(operation().path());
        record.setProperty(TerminalCommit.RESULT_SLOT, "some-other-slot");
        session.save();
        assertEquals(ArtifactServlet.NOT_REFERENCED, ask(OPERATION, SLOT).getStatus(),
                "an artifact this operation's result does not reference was served");
        record.setProperty(TerminalCommit.RESULT_SLOT, SLOT);
        session.save();
        final Node held = session.getNode(slot(SLOT).under(operation()).path());
        held.setProperty(ArtifactStore.DIGEST, Digest.of("something else"
                .getBytes(StandardCharsets.UTF_8)).rendered());
        session.save();
        final MockSlingHttpServletResponse refused = ask(OPERATION, SLOT);
        assertEquals(ArtifactServlet.NOT_VOUCHED_FOR, refused.getStatus(),
                "an artifact whose bytes are not the ones recorded was served");
        assertEquals(0, refused.getOutput().length,
                "a byte went out for an artifact this side cannot vouch for");
    }

    @Test
    @DisplayName("an operation nobody here holds is answered exactly as an empty slot is")
    void anoperationNobodyHoldsIsAnsweredAsAnEmptySlotIs() throws RepositoryException, IOException,
            ServletException {
        published("small.txt");
        final MockSlingHttpServletResponse elsewhere = ask(ELSEWHERE, SLOT);
        final MockSlingHttpServletResponse empty = ask(OPERATION, "a-slot-nobody-filled");
        assertEquals(empty.getStatus(), elsewhere.getStatus(),
                "somebody else's operation is told apart from a slot nobody filled");
        assertArrayEquals(empty.getOutput(), elsewhere.getOutput(),
                "the two answers differ in their bytes, which is a caller learning which"
                        + " operations exist");
        assertEquals(empty.getContentType(), elsewhere.getContentType());
    }

    @Test
    @DisplayName("no answer this route gives discloses a repository path")
    void noanswerDisclosesArepositoryPath() throws RepositoryException, IOException,
            ServletException {
        published("small.txt");
        final List<MockSlingHttpServletResponse> corpus = List.of(
                ask(OPERATION, SLOT),
                ask(OPERATION, "a-slot-nobody-filled"),
                ask(ELSEWHERE, SLOT),
                ask("not-an-identifier", SLOT),
                ask(OPERATION, "a slot with spaces"));
        for (final MockSlingHttpServletResponse answered : corpus) {
            final String everything = answered.getOutputAsString() + " "
                    + answered.getHeaderNames().stream()
                            .map(name -> name + ": " + answered.getHeader(name))
                            .reduce("", (all, header) -> all + " " + header);
            assertFalse(everything.contains(StatePath.ROOT),
                    "an answer disclosed where this agent keeps things: " + everything);
            assertFalse(everything.contains("jcr:"), everything);
        }
    }

    @Test
    @DisplayName("a stalled transfer ends at the idle bound and a large moving one does not")
    void astalledTransferEndsAndAmovingOneDoesNot() throws IOException {
        final byte[] whole = bytes(FIXTURES.resolve("large.txt"));
        final AdvancingTicker moving =
                new AdvancingTicker(TransferDeadlines.idleMilliseconds(CONTRACT) / 2);
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        assertEquals(whole.length,
                new ArtifactServlet(moving).transfer(new ByteArrayInputStream(whole), all,
                        CONTRACT),
                "a transfer that was moving the whole time was ended");
        assertArrayEquals(whole, all.toByteArray());
        assertTrue(moving.milliseconds() - NOW
                        > TransferDeadlines.idleMilliseconds(CONTRACT) * 2,
                "the moving transfer did not run past the idle bound, so nothing was proved");
        final AdvancingTicker stalling =
                new AdvancingTicker(TransferDeadlines.idleMilliseconds(CONTRACT));
        final ByteArrayOutputStream some = new ByteArrayOutputStream();
        assertNotEquals(whole.length,
                new ArtifactServlet(stalling).transfer(new ByteArrayInputStream(whole), some,
                        CONTRACT),
                "a transfer that stopped moving was carried to the end anyway");
        assertTrue(some.toByteArray().length > 0,
                "a stalled transfer sent nothing at all, so it was refused rather than ended");
    }

    @Test
    @DisplayName("a request nobody authenticated is refused, and one this build cannot read too")
    void arequestNobodyAuthenticatedIsRefused() throws RepositoryException, IOException,
            ServletException {
        published("small.txt");
        assertEquals(ArtifactServlet.REFUSED, ask("not-an-identifier", SLOT).getStatus(),
                "an operation this build cannot read was served");
        assertEquals(ArtifactServlet.REFUSED, ask(OPERATION, "a slot with spaces").getStatus(),
                "a slot this build cannot read was served");
        final SlingContext anonymous =
                new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(anonymous.resourceResolver());
        request.setMethod("GET");
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(ArtifactServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new ArtifactServlet().service(request, response);
        assertEquals(AuthenticationGate.STATUS, response.getStatus(),
                "an artifact was served to nobody in particular");
    }

    @Test
    @DisplayName("one registration answers both rows this path has, decided by the method")
    void oneregistrationAnswersBothRowsThisPathHas() throws RepositoryException, IOException,
            ServletException {
        published("small.txt");
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/octet-stream");
        request.setContent("bytes for work nobody declared".getBytes(StandardCharsets.UTF_8));
        request.setParameterMap(Map.of(
                ArtifactServlet.OPERATION_QUERY_MEMBER, OPERATION,
                ArtifactServlet.SLOT_QUERY_MEMBER, SLOT));
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(ArtifactServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new ArtifactServlet(new AdvancingTicker(0)).service(request, response);
        assertNotEquals(ShapeRefusal.WRONG_METHOD.status(), response.getStatus(),
                "a method the table gives this path was refused as one it does not");
        assertEquals(ArtifactIntakeServlet.NOT_WAITED_FOR, response.getStatus(),
                "bytes for work nothing is waiting on were not answered by the intake row");
        assertEquals(1, sourcesRegistering(ArtifactServlet.route().path()),
                "the path is registered more than once, and Sling registers a path-bound servlet"
                        + " by its path alone, so which row answers would be whichever component"
                        + " the resolver happened to pick");
    }

    private static long sourcesRegistering(String path) {
        try (java.util.stream.Stream<Path> sources =
                Files.walk(REPOSITORY.resolve("core/src/main/java"))) {
            return sources.filter(source -> source.toString().endsWith(".java"))
                    .map(ArtifactServletTest::read)
                    .filter(source -> source.contains("sling.servlet.paths=" + path))
                    .count();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the main sources are not readable", unreadable);
        }
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    /**
     * A clock a suite moves rather than waits for, which advances by a fixed step each time it is
     * asked how long the transfer has been going.
     *
     * @param step how much time passes between one look and the next
     */
    private static final class AdvancingTicker implements StreamTicker {

        @Override
        public long elapsedMilliseconds() {
            return milliseconds();
        }


        private static final long serialVersionUID = 1L;

        private final long step;

        private long now = NOW;

        private AdvancingTicker(long step) {
            this.step = step;
        }

        @Override
        public long milliseconds() {
            now = now + step;
            return now;
        }

        @Override
        public void pause(long milliseconds) {
            now = now + milliseconds;
        }
    }

    private MockSlingHttpServletResponse ask(String operation, String slot)
            throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("GET");
        request.setParameterMap(Map.of(
                ArtifactServlet.OPERATION_QUERY_MEMBER, operation,
                ArtifactServlet.SLOT_QUERY_MEMBER, slot));
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(ArtifactServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new ArtifactServlet(new AdvancingTicker(0)).service(request, response);
        return response;
    }

    private Session published(String fixture) throws RepositoryException, IOException {
        final Session session = prepared();
        if (session.nodeExists(slot(SLOT).under(operation()).path())) {
            session.getNode(slot(SLOT).under(operation()).path()).remove();
            session.save();
        }
        try (InputStream content = new ByteArrayInputStream(bytes(FIXTURES.resolve(fixture)))) {
            assertInstanceOf(ArtifactStore.Published.class,
                    ArtifactStore.publish(session, caller(), operation(),
                            new ArtifactStore.Publication(slot(SLOT),
                                    bytes(FIXTURES.resolve(fixture)).length, content),
                            NOW, CONTRACT),
                    fixture + " was not published");
        }
        final Node record = session.getNode(operation().path());
        record.setProperty(TerminalCommit.RESULT_SLOT, SLOT);
        session.save();
        return session;
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        ArtifactStore.prepare(session, caller());
        walked(session, operation().path());
        return session;
    }

    private StatePath.Caller caller() {
        final String user = sling.resourceResolver().getUserID();
        return assertInstanceOf(StatePath.Held.class,
                StatePath.caller(user == null ? "admin" : user), "the caller was refused").caller();
    }

    private static StatePath operation() {
        return StatePath.operation(generation(), identifier());
    }

    private static AgentOperationIdentifier identifier() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(OPERATION, CONTRACT),
                "the operation identifier was refused").identifier();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static ArtifactSlot slot(String named) {
        return assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of(named),
                named + " is not a slot").slot();
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
