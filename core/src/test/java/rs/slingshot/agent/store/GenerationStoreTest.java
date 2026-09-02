// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Which incarnation of the store a client is talking to, and what happens when it is another one.
 *
 * <p>Establishing is proved through the claim primitive rather than by starting two processes: the
 * property is that two writers establishing at once produce one record, and the primitive that
 * decides it is the one under test everywhere else too.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class GenerationStoreTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("the first generation is established once, however many writers establish it")
    void thefirstGenerationIsEstablishedOnce() throws RepositoryException {
        final Session session = prepared();
        assertEquals(EventStoreGeneration.FIRST, established(session).number());
        assertEquals(EventStoreGeneration.FIRST, established(session).number(),
                "a second establishment moved the generation");
        assertEquals(List.of(EventStoreGeneration.FIRST), GenerationStore.served(session));
    }

    @Test
    @DisplayName("an absent record on a tree that is there is refused rather than created")
    void anAbsentRecordIsRefusedRatherThanCreated() throws RepositoryException {
        final Session session = prepared();
        final GenerationStore.Refused refused = assertInstanceOf(GenerationStore.Refused.class,
                GenerationStore.serving(session), "a generation appeared out of nothing");
        assertEquals(GenerationStore.Refusal.NO_RECORD, refused.refusal());
        assertTrue(refused.detail().contains(GenerationStore.record().path()), refused.detail());
        assertTrue(!session.nodeExists(GenerationStore.record().path()),
                "reading for a generation created one");
    }

    @Test
    @DisplayName("a tree that was never prepared is a different refusal from a record that is gone")
    void anUnpreparedDeploymentIsItsOwnRefusal() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        assertEquals(GenerationStore.Refusal.NO_TREE,
                assertInstanceOf(GenerationStore.Refused.class, GenerationStore.serving(session))
                        .refusal());
        assertEquals(GenerationStore.Refusal.NO_TREE,
                assertInstanceOf(GenerationStore.Refused.class, GenerationStore.establish(session))
                        .refusal(),
                "an unprepared deployment established a generation anyway");
    }

    @Test
    @DisplayName("a repeat and a decrease are two refusals, each naming both numbers")
    void arepeatAndADecreaseAreDistinct() throws RepositoryException {
        final Session session = prepared();
        GenerationStore.establish(session);
        assertEquals(3, assertInstanceOf(GenerationStore.Held.class,
                GenerationStore.rotate(session, generation(3))).generation().number());
        final GenerationStore.Refused repeated = assertInstanceOf(GenerationStore.Refused.class,
                GenerationStore.rotate(session, generation(1)),
                "a generation this store had already served was served again");
        assertEquals(GenerationStore.Refusal.ALREADY_SERVED, repeated.refusal());
        final GenerationStore.Refused decreased = assertInstanceOf(GenerationStore.Refused.class,
                GenerationStore.rotate(session, generation(2)),
                "a store went back to an earlier incarnation");
        assertEquals(GenerationStore.Refusal.BEFORE_THE_ONE_SERVED, decreased.refusal());
        assertTrue(decreased.detail().contains("2") && decreased.detail().contains("3"),
                decreased.detail());
    }

    @Test
    @DisplayName("a served, a retained, and an unknown generation are three answers")
    void thethreeMembershipsAreDistinct() throws RepositoryException {
        final Session session = prepared();
        GenerationStore.establish(session);
        GenerationStore.rotate(session, generation(2));
        assertEquals(GenerationStore.Membership.SERVING,
                GenerationStore.membership(session, generation(2)));
        assertEquals(GenerationStore.Membership.RETAINED,
                GenerationStore.membership(session, generation(1)));
        assertEquals(GenerationStore.Membership.UNKNOWN,
                GenerationStore.membership(session, generation(9)));
    }

    @Test
    @DisplayName("every derived path carries the generation it belongs to")
    void everyDerivedPathCarriesItsGeneration() throws RepositoryException {
        final AgentOperationIdentifier identifier = assertInstanceOf(
                AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(Digest.of("one operation".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)).rendered(), contract()))
                .identifier();
        assertTrue(StatePath.operation(generation(1), identifier).path().contains("/g1/"),
                "an operation's path does not say which incarnation it belongs to");
        assertTrue(StatePath.operation(generation(2), identifier).path().contains("/g2/"),
                "two incarnations derive one path");
    }

    private GenerationStore.Held establishedOutcome(Session session) throws RepositoryException {
        return assertInstanceOf(GenerationStore.Held.class, GenerationStore.establish(session),
                "the first generation was not established");
    }

    private EventStoreGeneration established(Session session) throws RepositoryException {
        return establishedOutcome(session).generation();
    }

    private static EventStoreGeneration generation(long number) {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(number),
                number + " is not a generation").generation();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        if (!session.nodeExists("/var")) {
            session.getRootNode().addNode("var", "nt:unstructured");
        }
        if (!session.nodeExists(StatePath.ROOT)) {
            session.getNode("/var").addNode("slingshot-agent", "nt:unstructured");
        }
        session.save();
        return session;
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
