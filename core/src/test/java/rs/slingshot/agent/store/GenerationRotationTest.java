// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;
import rs.slingshot.agent.discovery.CapabilityDocument;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Rotation as something somebody chooses, with the old incarnations kept while anybody reads them.
 *
 * <p>The refusal is the interesting outcome. An operator told "not yet, generation three is
 * answered about until this instant" can wait or can shorten a retention on purpose; an operator
 * whose rotation quietly dropped it finds out when a client's lookup returns a confident
 * nothing.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class GenerationRotationTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/generation-rotation");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a rotation moves the store and keeps the incarnation it left")
    void arotationKeepsWhatItLeaves() throws RepositoryException {
        final Session session = established();
        final GenerationRotation.Rotated rotated = assertInstanceOf(GenerationRotation.Rotated.class,
                GenerationRotation.rotate(session, generationOf(2), NOW, CONTRACT),
                "the store did not rotate");
        assertEquals(2, rotated.serving().number());
        assertEquals(List.of(1L), rotated.retained().stream()
                        .map(one -> one.generation().number()).toList(),
                "the incarnation the store left is not the one it kept");
        assertEquals(NOW + GenerationRotation.longestRetention(CONTRACT),
                rotated.retained().getFirst().retainedUntilUnixMilliseconds(),
                "a retired incarnation is answered about for something other than the longest"
                        + " anything in it is kept");
        assertEquals(2, assertInstanceOf(GenerationStore.Held.class,
                GenerationStore.serving(session)).generation().number());
    }

    @Test
    @DisplayName("two nodes rotating at once leave one new incarnation")
    void twonodesRotatingAtOnceLeaveOne() throws RepositoryException, LoginException {
        final Session second = second();
        final Session session = established();
        second.refresh(false);
        assertInstanceOf(GenerationRotation.Rotated.class,
                GenerationRotation.rotate(session, generationOf(2), NOW, CONTRACT));
        second.refresh(false);
        assertEquals(GenerationRotation.Refusal.STORE_REFUSED, GenerationRotation.refusalIn(
                GenerationRotation.rotate(second, generationOf(2), NOW, CONTRACT)).orElseThrow()
                .refusal(), "two nodes both rotated to one incarnation");
        assertEquals(2, assertInstanceOf(GenerationStore.Held.class,
                GenerationStore.serving(session)).generation().number(),
                "the store is serving something other than the one rotation that happened");
        assertEquals(1, GenerationRotation.retained(session).size(),
                "one rotation kept more than one incarnation");
        second.logout();
    }

    @Test
    @DisplayName("prior incarnations are kept up to the bound and no rotation drops a live one")
    void priorincarnationsAreKeptUpToTheBound() throws RepositoryException {
        final AgentContract two = contractWith("maximum_prior_generations", 2L);
        final Session session = established();
        assertInstanceOf(GenerationRotation.Rotated.class,
                GenerationRotation.rotate(session, generationOf(2), NOW, two));
        assertInstanceOf(GenerationRotation.Rotated.class,
                GenerationRotation.rotate(session, generationOf(3), NOW, two));
        assertEquals(2, GenerationRotation.retained(session).size(),
                "the store kept a different number of incarnations than the bound allows");
        final GenerationRotation.Refused refused = GenerationRotation.refusalIn(
                GenerationRotation.rotate(session, generationOf(4), NOW, two)).orElseThrow();
        assertEquals(GenerationRotation.Refusal.INSIDE_A_RETENTION, refused.refusal());
        assertTrue(refused.detail().contains("1")
                        && refused.detail().contains(String.valueOf(
                                NOW + GenerationRotation.longestRetention(two))),
                refused.detail());
        assertEquals(3, assertInstanceOf(GenerationStore.Held.class,
                GenerationStore.serving(session)).generation().number(),
                "a refused rotation moved the store anyway");
        final long past = NOW + GenerationRotation.longestRetention(two);
        assertInstanceOf(GenerationRotation.Rotated.class,
                GenerationRotation.rotate(session, generationOf(4), past, two),
                "a rotation was refused after the oldest incarnation stopped being read");
        assertEquals(List.of(2L, 3L), GenerationRotation.retained(session).stream()
                        .map(one -> one.generation().number()).toList(),
                "the incarnation that was dropped is not the oldest one");
    }

    @Test
    @DisplayName("a retained incarnation may be read and never added to, and a retired one neither")
    void aretainedIncarnationMayBeReadAndNotWritten() throws RepositoryException {
        final AgentContract one = contractWith("maximum_prior_generations", 1L);
        final Session session = established();
        GenerationRotation.rotate(session, generationOf(2), NOW, one);
        assertInstanceOf(GenerationRotation.Serving.class,
                GenerationRotation.accessTo(session, generationOf(2)),
                "the incarnation being served is not the one that may be written");
        final GenerationRotation.Readable readable = assertInstanceOf(
                GenerationRotation.Readable.class,
                GenerationRotation.accessTo(session, generationOf(1)),
                "the incarnation just retired cannot be read, so reconciliation cannot finish");
        assertEquals(1, readable.generation().generation().number());
        final GenerationRotation.Retired retired = assertInstanceOf(
                GenerationRotation.Retired.class,
                GenerationRotation.accessTo(session, generationOf(9)),
                "an incarnation this store never had was said to be readable");
        assertTrue(retired.rendered().contains("9") && retired.rendered().contains("2"),
                retired.rendered());
        assertEquals(GenerationStore.Membership.RETAINED,
                GenerationStore.membership(session, generationOf(1)),
                "the store and the rotation disagree about what a retained incarnation is");
        assertEquals(GenerationStore.Membership.UNKNOWN,
                GenerationStore.membership(session, generationOf(9)),
                "an incarnation this store never had was said to be one of its own");
        assertEquals(1, identity().generation().number(),
                "the fixture naming work from before the rotation names the wrong incarnation");
    }

    @Test
    @DisplayName("after a rotation the capability document reports the incarnation now served")
    void thecapabilityDocumentReportsTheNewIncarnation() throws RepositoryException {
        final Session session = established();
        GenerationRotation.rotate(session, generationOf(2), NOW, CONTRACT);
        final EventStoreGeneration serving = assertInstanceOf(GenerationStore.Held.class,
                GenerationStore.serving(session)).generation();
        final String rendered = assertInstanceOf(CapabilityDocument.Held.class,
                CapabilityDocument.of(new AdvertisedCapabilities(serving, digest("canonical"),
                        List.of(), AdvertisedCapabilities.ContinuationAuthority.READY,
                        digest("transport")),
                        CONTRACT.value(ContractLimit.MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES)),
                "the capability document was refused").document().render();
        assertTrue(rendered.contains("\"" + CapabilityDocument.GENERATION + "\":2"),
                "the capability document reports an incarnation this store is not serving: "
                        + rendered);
    }

    private static OperationIdentity identity() {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document("operation-in-a-retired-generation.json"), CONTRACT),
                "the operation identity was refused").identity();
    }

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static EventStoreGeneration generationOf(long number) {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(number),
                number + " is not a generation").generation();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private Session second() throws LoginException {
        return java.util.Objects.requireNonNull(
                java.util.Objects.requireNonNull(sling.getService(ResourceResolverFactory.class),
                                "this context registers no resolver factory")
                        .getResourceResolver(Map.of()).adaptTo(Session.class),
                "a second session over the same repository is not available");
    }

    private Session established() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, StatePath.ROOT);
        assertInstanceOf(GenerationStore.Held.class, GenerationStore.establish(session),
                "the store's first incarnation was not established");
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

    private static AgentContract contractWith(String bound, long value) {
        final Map<String, Long> overrides = Map.of(bound, value);
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
