// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
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
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.JobEvent;

/**
 * Bytes published so that a reference to them is always good, or published not at all.
 *
 * <p>The size-mismatch case is the one worth reading first: what it asserts is not only that the
 * refusal happens but that nothing was ever committed and that the reservation came back exactly,
 * because a store that leaks capacity on every bad transfer is a store somebody can fill by getting
 * it wrong repeatedly.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ArtifactStoreTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/artifact-store");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("one slot and several are written and read back byte for byte")
    void oneslotAndSeveralAreReadBackByteForByte() throws RepositoryException, IOException {
        final Session session = prepared();
        final byte[] small = bytes(FIXTURES.resolve("small.txt"));
        final ArtifactRecord only = assertInstanceOf(ArtifactStore.Published.class,
                publish(session, "the-only-slot", small), "one artifact was not published")
                .record();
        assertEquals(small.length, only.byteCount());
        assertEquals(Digest.of(small).rendered(), only.digest().rendered());
        assertArrayEquals(small, read(session, "the-only-slot"),
                "what came back is not what was written");
        for (final String slot : List.of("the-first-of-several", "the-second-of-several")) {
            final byte[] part = (slot + " holds its own bytes").getBytes(StandardCharsets.UTF_8);
            assertInstanceOf(ArtifactStore.Published.class, publish(session, slot, part),
                    slot + " was not published");
            assertArrayEquals(part, read(session, slot), slot + " came back as somebody else");
        }
        assertEquals(3, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT),
                "three artifacts were not counted as three");
    }

    @Test
    @DisplayName("a reader can check the bytes itself rather than trust what the store says")
    void areaderCanCheckTheBytesItself() throws RepositoryException, IOException {
        final Session session = prepared();
        final byte[] large = bytes(FIXTURES.resolve("large.txt"));
        final ArtifactRecord published = assertInstanceOf(ArtifactStore.Published.class,
                publish(session, "the-only-slot", large)).record();
        try (InputStream held = ArtifactStore.open(session, operation(), slot("the-only-slot"))
                .orElseThrow()) {
            assertEquals(published.digest().rendered(), Digest.of(held).rendered(),
                    "the bytes a reader gets are not the bytes the record says they are");
        }
        assertEquals(large.length, ArtifactStore.read(session, operation(), slot("the-only-slot"))
                .orElseThrow().byteCount());
        assertTrue(large.length > Digest.READ_BUFFER_BYTES,
                "this artifact is not larger than one buffer, so it proves nothing about streaming");
    }

    @Test
    @DisplayName("an occupied slot is refused, and a writer that did not see it takes nothing")
    void anoccupiedSlotIsRefused() throws RepositoryException, IOException {
        final Session session = prepared();
        final byte[] first = "the bytes that got there first".getBytes(StandardCharsets.UTF_8);
        assertInstanceOf(ArtifactStore.Published.class, publish(session, "the-only-slot", first));
        assertEquals(ArtifactStore.Refusal.SLOT_TAKEN, ArtifactStore.refusalIn(
                publish(session, "the-only-slot", "second".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow().refusal());
        final byte[] late = "the bytes that arrived late".getBytes(StandardCharsets.UTF_8);
        assertEquals(ArtifactStore.Refusal.SLOT_TAKEN, ArtifactStore.refusalIn(
                ArtifactStore.publish(blindTo(session, slot("the-only-slot").under(operation())
                                .path()), caller(),
                        operation(), new ArtifactStore.Publication(slot("the-only-slot"),
                                late.length, new ByteArrayInputStream(late)), NOW, CONTRACT))
                .orElseThrow().refusal(),
                "two writers both published into one slot");
        assertArrayEquals(first, read(session, "the-only-slot"),
                "the writer that lost overwrote the one that won");
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT),
                "the writer that lost kept the row it reserved");
        assertEquals(first.length, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES,
                CONTRACT), "the writer that lost kept the bytes it reserved");
    }

    @Test
    @DisplayName("a size that differs from what was declared commits nothing and keeps nothing")
    void asizeThatDiffersCommitsNothing() throws RepositoryException {
        final Session session = prepared();
        final byte[] content = "fewer bytes than were promised".getBytes(StandardCharsets.UTF_8);
        final ArtifactStore.Refused refused = ArtifactStore.refusalIn(ArtifactStore.publish(session,
                caller(), operation(), new ArtifactStore.Publication(slot("the-only-slot"),
                        content.length + 1, new ByteArrayInputStream(content)), NOW, CONTRACT))
                .orElseThrow();
        assertEquals(ArtifactStore.Refusal.SIZE_DIFFERS, refused.refusal());
        assertTrue(refused.detail().contains(String.valueOf(content.length))
                && refused.detail().contains(String.valueOf(content.length + 1)),
                refused.detail());
        assertFalse(session.nodeExists(slot("the-only-slot").under(operation()).path()),
                "a refused artifact left something reachable behind");
        assertTrue(ArtifactStore.read(session, operation(), slot("the-only-slot")).isEmpty());
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT),
                "a refused artifact kept the row it reserved");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT),
                "a refused artifact kept the bytes it reserved");
    }

    @Test
    @DisplayName("capacity is taken from the declared size before a byte is read")
    void capacityIsTakenBeforeAbyteIsRead() throws RepositoryException, IOException {
        final Session session = prepared();
        final byte[] content = bytes(FIXTURES.resolve("large.txt"));
        final AgentContract small = contractWith(Map.of(
                "maximum_current_generation_artifact_bytes", (long) content.length / 2,
                "maximum_caller_current_generation_artifact_bytes", (long) content.length / 2));
        try (Refusing refusing = new Refusing(content)) {
            final CapacityLedger.Refused refused = assertInstanceOf(ArtifactStore.AtCapacity.class,
                    ArtifactStore.publish(session, caller(), operation(),
                            new ArtifactStore.Publication(slot("the-only-slot"), content.length,
                                    refusing), NOW, small),
                    "a store with no room for the declared size read the bytes anyway").refusal();
            assertEquals(AccountedQuantity.ARTIFACT_BYTES, refused.quantity());
            assertEquals(0, refusing.bytesRead(),
                    "a byte was read before the room for it was taken");
        }
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, small),
                "a refused reservation kept the row it took first");
    }

    @Test
    @DisplayName("both ways of reading a stream count what passes through, and neither holds it")
    void bothWaysOfReadingCount() throws IOException {
        final byte[] content = "a few bytes read two different ways".getBytes(StandardCharsets.UTF_8);
        try (ArtifactStore.Counted oneAtAtime = counting(content)) {
            while (oneAtAtime.read() >= 0) {
                assertTrue(oneAtAtime.bytes() <= content.length, "more arrived than was sent");
            }
            assertEquals(content.length, oneAtAtime.bytes(),
                    "reading one byte at a time counted something other than what arrived");
        }
        try (ArtifactStore.Counted inBlocks = counting(content)) {
            assertEquals(content.length, inBlocks.readAllBytes().length);
            assertEquals(content.length, inBlocks.bytes(),
                    "reading in blocks counted something other than what arrived");
            assertEquals(Digest.of(content).rendered(),
                    rs.slingshot.agent.digest.DigestValue.ofBytes(inBlocks.digested().digest())
                            .rendered(),
                    "what passed through digests to something other than what was sent");
        }
    }

    @Test
    @DisplayName("a store that counted nothing is told so rather than told it is full")
    void astoreThatCountedNothingIsToldSo() throws RepositoryException {
        final Session session = prepared();
        final byte[] content = "bytes nobody counted".getBytes(StandardCharsets.UTF_8);
        assertEquals(AccountedQuantity.ARTIFACT_ROWS, assertInstanceOf(
                ArtifactStore.NotCounted.class,
                ArtifactStore.publish(session, caller("a-caller-nobody-prepared"), operation(),
                        new ArtifactStore.Publication(slot("the-only-slot"), content.length,
                                new ByteArrayInputStream(content)), NOW, CONTRACT),
                "a store with no counters for this caller said it had room").notCounted()
                .quantity());
        assertFalse(session.nodeExists(slot("the-only-slot").under(operation()).path()),
                "an artifact nothing counted was written anyway");
    }

    @Test
    @DisplayName("nothing here holds an artifact, whatever size it is")
    void nothingHereHoldsAnArtifact() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/ArtifactStore.java"));
        for (final String holding : List.of("readAllBytes", "toByteArray", "new byte[",
                "ByteArrayOutputStream", "readNBytes")) {
            assertFalse(source.contains(holding),
                    "the store reaches for " + holding + ", which holds an artifact in memory");
        }
        assertTrue(source.contains("createBinary"),
                "the bytes are not handed to the store as a stream");
    }

    @Test
    @DisplayName("a slot name that is not one is refused before anything is reserved")
    void aslotNameThatIsNotOneIsRefused() throws RepositoryException {
        final Session session = prepared();
        assertEquals(ArtifactSlot.Refusal.CARRIES_A_SEPARATOR, refusal("carries-a-separator"));
        assertEquals(ArtifactSlot.Refusal.CLIMBS_OUT_OF_THE_TREE, refusal("climbs-out"));
        assertEquals(ArtifactSlot.Refusal.NOT_A_NAME, refusal("not-a-name"));
        assertEquals(ArtifactSlot.Refusal.EMPTY, refusal("empty"));
        assertEquals(ArtifactStore.Refusal.NO_OPERATION, ArtifactStore.refusalIn(
                ArtifactStore.publish(session, caller(), elsewhere(),
                        new ArtifactStore.Publication(slot("the-only-slot"), 0,
                                new ByteArrayInputStream(new byte[0])), NOW, CONTRACT))
                .orElseThrow().refusal());
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT),
                "a refusal before the write reserved something anyway");
    }

    /** A stream that says how much of it has been read, so "before the first byte" is checkable. */
    private static final class Refusing extends InputStream {

        private final byte[] content;

        private final java.util.concurrent.atomic.AtomicInteger read =
                new java.util.concurrent.atomic.AtomicInteger();

        Refusing(byte[] content) {
            this.content = content.clone();
        }

        @Override
        public int read() {
            if (read.get() >= content.length) {
                return -1;
            }
            return content[read.getAndIncrement()];
        }

        int bytesRead() {
            return read.get();
        }
    }

    private ArtifactStore.Outcome publish(Session session, String slot, byte[] content)
            throws RepositoryException {
        return ArtifactStore.publish(session, caller(), operation(),
                new ArtifactStore.Publication(slot(slot), content.length,
                        new ByteArrayInputStream(content)), NOW, CONTRACT);
    }

    private byte[] read(Session session, String slot) throws RepositoryException, IOException {
        try (InputStream held = ArtifactStore.open(session, operation(), slot(slot))
                .orElseThrow()) {
            return held.readAllBytes();
        }
    }

    private static Session blindTo(Session session, String hidden) {
        return (Session) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class},
                (proxy, method, arguments) -> "nodeExists".equals(method.getName())
                        && hidden.equals(arguments[0])
                        ? Boolean.FALSE
                        : method.invoke(session, arguments));
    }

    private static ArtifactSlot slot(String named) {
        return assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of(fixture(named)),
                named + " is not a slot").slot();
    }

    private static ArtifactSlot.Refusal refusal(String named) {
        return ArtifactSlot.refusalIn(ArtifactSlot.of(fixture(named))).orElseThrow().refusal();
    }

    private static String fixture(String name) {
        final DocumentValue.Mapping slots = assertInstanceOf(DocumentValue.Mapping.class,
                document("slots.json"), "the slot fixtures are not an object");
        return assertInstanceOf(DocumentValue.Text.class, slots.member(name).orElseThrow(),
                name + " is not a fixture").value();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath operation() {
        final JobEvent held = assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document("operation.json"), generation(), CONTRACT),
                "the operation fixture is not an event").event();
        return StatePath.operation(held.generation(), held.identifier());
    }

    private static StatePath elsewhere() {
        return StatePath.operation(generation(), assertInstanceOf(
                rs.slingshot.agent.identity.AgentOperationIdentifier.Held.class,
                rs.slingshot.agent.identity.AgentOperationIdentifier.of(
                        Digest.of("an operation nothing holds".getBytes(StandardCharsets.UTF_8))
                                .rendered(), CONTRACT),
                "the absent operation's own name was refused").identifier());
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static ArtifactStore.Counted counting(byte[] content) {
        return new ArtifactStore.Counted(new java.security.DigestInputStream(
                new ByteArrayInputStream(content), digestAlgorithm()));
    }

    private static java.security.MessageDigest digestAlgorithm() {
        try {
            return java.security.MessageDigest.getInstance(Digest.ALGORITHM);
        } catch (final java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException(Digest.ALGORITHM + " is required of every platform",
                    absent);
        }
    }

    private static StatePath.Caller caller() {
        return caller("the-publishing-caller");
    }

    private static StatePath.Caller caller(String name) {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller(name),
                name + " was refused as a caller").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, operation().path());
        ArtifactStore.prepare(session, caller());
        return session;
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }

    private static AgentContract contractWith(Map<String, Long> overrides) {
        final StringBuilder rewritten = new StringBuilder();
        read(REPOSITORY.resolve("support/agent-contract.toml")).lines().forEach(line -> {
            final String name = line.contains("=") ? line.substring(0, line.indexOf('=')).strip()
                    : "";
            rewritten.append(overrides.containsKey(name) ? name + " = " + overrides.get(name)
                            : line)
                    .append('\n');
        });
        final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        return assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load(document, AgentContract.digestOf(document)),
                "the shrunken contract is not one this build reads").contract();
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

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
