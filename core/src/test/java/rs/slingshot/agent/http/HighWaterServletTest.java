// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.HighWaterMark;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionLedger;
import rs.slingshot.agent.store.SubscriptionRecord;
import rs.slingshot.agent.wire.EventSequence;

/**
 * The number this side actually has, which is the one a reconnecting subscriber does not.
 *
 * <p>Three answers rather than two, because a subscription this side never had and one it swept are
 * different situations for a subscriber: the first is a mistake, and the second is a subscription
 * that has to be taken again.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class HighWaterServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final AgentContract CONTRACT = contract();

    private static final String SUBSCRIPTION = "following-daemon-one";

    private static final String ANOTHER_SUBSCRIPTION = "following-daemon-two";

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a live subscription answers its own cursor and this store's incarnation")
    void alivesubscriptionAnswersItsOwnCursor() throws RepositoryException, IOException,
            ServletException {
        final Session session = subscribed();
        assertInstanceOf(HighWaterMark.Advanced.class, HighWaterMark.advance(session,
                        identifier(SUBSCRIPTION), sequence(2), NOW),
                "the mark would not move");
        final MockSlingHttpServletResponse answered = ask(SUBSCRIPTION, 0);
        assertEquals(OperationLookupServlet.SERVED, answered.getStatus(),
                answered.getOutputAsString());
        assertEquals("{\"agent_event_store_generation\":1,\"daemon_subscription_identifier\":\""
                        + SUBSCRIPTION + "\",\"events_shown\":3}",
                answered.getOutputAsString(),
                "the answer is not the cursor the store holds");
    }

    @Test
    @DisplayName("a subscription nobody has and one this side swept are two different answers")
    void anunknownAndAsweptSubscriptionAreDifferent() throws RepositoryException, IOException,
            ServletException {
        final Session session = subscribed();
        assertEquals(HighWaterServlet.UNKNOWN, ask("a-subscription-nobody-took", 0).getStatus(),
                "a subscription nobody took was answered with something");
        final Node record = session.getNode(
                SubscriptionRecord.pathOf(identifier(SUBSCRIPTION)).path());
        record.setProperty(SubscriptionRecord.LAST_ADVANCED_AT, LONG_AGO);
        session.save();
        assertEquals(HighWaterServlet.EXPIRED, ask(SUBSCRIPTION, 0).getStatus(),
                "a subscription this side swept was answered as though it were live");
    }

    /** An instant far enough back that everything this side keeps has been kept long enough. */
    private static final long LONG_AGO = 1;

    @Test
    @DisplayName("a cursor into another incarnation is a reset naming the one this store serves")
    void acursorIntoAnotherIncarnationIsAreset() throws RepositoryException, IOException,
            ServletException {
        subscribed();
        final MockSlingHttpServletResponse answered = ask(SUBSCRIPTION, ANOTHER_GENERATION);
        assertEquals(HighWaterServlet.RESET, answered.getStatus(),
                "a cursor into an incarnation this store does not serve was answered as a position");
        assertTrue(answered.getOutputAsString().contains("\"agent_event_store_generation\":1"),
                "the reset does not name the incarnation this store serves: "
                        + answered.getOutputAsString());
    }

    /** An incarnation this store does not serve. */
    private static final long ANOTHER_GENERATION = 7;

    @Test
    @DisplayName("no answer names any subscription but the one that was asked about")
    void noanswerNamesAnotherSubscription() throws RepositoryException, IOException,
            ServletException {
        final Session session = subscribed();
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                SubscriptionLedger.subscribe(session, caller(), ANOTHER_SUBSCRIPTION,
                        generation(), NOW, CONTRACT),
                "the second subscription was not taken");
        final String answered = ask(SUBSCRIPTION, 0).getOutputAsString();
        assertFalse(answered.contains(ANOTHER_SUBSCRIPTION),
                "an answer named a subscription nobody asked about: " + answered);
        assertEquals(3, answered.split("\":", -1).length - 1,
                "an answer carries a member this build did not mean to answer with: " + answered);
    }

    @Test
    @DisplayName("a body that is not a subscription at all is refused rather than guessed at")
    void abodyThatIsNotAsubscriptionIsRefused() throws RepositoryException, IOException,
            ServletException {
        subscribed();
        assertEquals(OperationLookupServlet.REFUSED,
                asking("this is not a document").getStatus());
        assertEquals(OperationLookupServlet.REFUSED,
                asking("{\"daemon_subscription_identifier\":\"a name with spaces\"}").getStatus());
    }

    private MockSlingHttpServletResponse ask(String subscription, long generation)
            throws IOException, ServletException {
        return asking("{\"" + HighWaterServlet.SUBSCRIPTION + "\":\"" + subscription + "\""
                + (generation > 0 ? ",\"" + HighWaterServlet.GENERATION + "\":" + generation : "")
                + "}");
    }

    private MockSlingHttpServletResponse asking(String body) throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(HighWaterServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new HighWaterServlet().service(request, response);
        return response;
    }

    private Session subscribed() throws RepositoryException {
        final Session session = prepared();
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                SubscriptionLedger.subscribe(session, caller(), SUBSCRIPTION, generation(), NOW,
                        CONTRACT), "the subscription was not taken");
        return session;
    }

    private static SubscriptionRecord.Identifier identifier(String subscription) {
        return assertInstanceOf(SubscriptionRecord.Held.class,
                SubscriptionRecord.identifier(subscription, CONTRACT),
                subscription + " is not an identifier").identifier();
    }

    private static EventSequence sequence(long number) {
        return assertInstanceOf(EventSequence.Held.class, EventSequence.of(number),
                number + " is not a sequence").sequence();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private StatePath.Caller caller() {
        final String user = sling.resourceResolver().getUserID();
        return assertInstanceOf(StatePath.Held.class,
                StatePath.caller(user == null ? "admin" : user), "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        SubscriptionLedger.prepare(session, caller());
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

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
