// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.Replicator;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.jcr.Session;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.platform.ContentAdmission;

/**
 * The one place this build talks to Adobe's replication service.
 *
 * <p>Two things are worth proving and neither is about replication. The session is <em>borrowed</em>
 * from the caller's own resolver rather than obtained, so a caller offers exactly what they could
 * have offered by hand; and a service that stops part-way through is reported as what it actually
 * took rather than as a failure, because a queue is one of the few places where partial progress is
 * genuinely recoverable and pretending otherwise would have somebody offer the whole subtree
 * again.</p>
 *
 * <p>The resolver and the session here are proxies rather than doubles from a testing library. This
 * module compiles against one Adobe artifact and nothing else on purpose — a second testing
 * dependency graph to stand up two interfaces would cost more than the interfaces are worth.</p>
 */
final class ReplicatorAdmissionTest {

    private static final List<String> PATHS =
            List.of("/content/site/one", "/content/site/two", "/content/site/three");

    @Test
    @DisplayName("the session is borrowed from the caller's own resolver rather than obtained")
    void thesessionIsBorrowed() {
        final Recording replicator = new Recording(PATHS.size());
        final ContentAdmission.Outcome outcome =
                new ReplicatorAdmission(replicator.service()).offer(PATHS, resolverWithSession());
        assertEquals(PATHS.size(),
                assertInstanceOf(ContentAdmission.Admitted.class, outcome,
                        "the offer was refused").acceptedItemCount());
        assertEquals(PATHS, replicator.offered(),
                "the service was handed something other than what the caller named");
        assertEquals(1, replicator.sessions().stream().distinct().count(),
                "the service was handed more than one session for one offer, so what it acted as"
                        + " is not what the caller is");
    }

    @Test
    @DisplayName("a caller with no repository session is refused, and nothing is offered")
    void nosessionMeansNothingIsOffered() {
        final Recording replicator = new Recording(PATHS.size());
        final ContentAdmission.Outcome outcome =
                new ReplicatorAdmission(replicator.service()).offer(PATHS, resolverWithoutSession());
        final ContentAdmission.Rejected rejected = assertInstanceOf(
                ContentAdmission.Rejected.class, outcome,
                "a caller with no session was reported as having had their content offered");
        assertTrue(rejected.detail().contains("nothing was offered"),
                "the refusal does not say that nothing happened, and a caller told only that it"
                        + " failed does not know whether to offer again: " + rejected.detail());
        assertEquals(List.of(), replicator.offered(),
                "the service was asked to replicate without a session");
    }

    @Test
    @DisplayName("a service that stops part-way reports what it took, not that everything failed")
    void partialProgressIsReportedAsProgress() {
        final Recording replicator = new Recording(2);
        final ContentAdmission.Outcome outcome =
                new ReplicatorAdmission(replicator.service()).offer(PATHS, resolverWithSession());
        assertEquals(2,
                assertInstanceOf(ContentAdmission.Admitted.class, outcome,
                        "a service that took two of three was reported as having taken none")
                        .acceptedItemCount(),
                "a queue is one of the few places where partial progress is genuinely recoverable,"
                        + " and reporting it as a failure would have somebody offer the whole"
                        + " subtree again");
        assertEquals(PATHS.subList(0, 2), replicator.offered());
    }

    @Test
    @DisplayName("a service that took nothing at all is a refusal rather than an empty admission")
    void takingNothingIsARefusal() {
        final ContentAdmission.Outcome outcome =
                new ReplicatorAdmission(new Recording(0).service()).offer(PATHS, resolverWithSession());
        final ContentAdmission.Rejected rejected = assertInstanceOf(
                ContentAdmission.Rejected.class, outcome,
                "a service that took nothing was reported as having admitted nought items, which"
                        + " reads as success with an empty subtree");
        assertTrue(rejected.detail().contains("3"),
                "the refusal does not say how many were offered: " + rejected.detail());
    }

    /**
     * A replication service that takes a fixed number of paths and then refuses.
     *
     * <p>A proxy rather than a class implementing the interface, because the interface has a dozen
     * methods this test has no opinion about and standing them all up would bury the two lines that
     * matter.</p>
     */
    private static final class Recording {

        private final int takes;
        private final List<String> offered = new ArrayList<>();
        private final List<Session> sessions = new ArrayList<>();

        Recording(int takes) {
            this.takes = takes;
        }

        List<String> offered() {
            return List.copyOf(offered);
        }

        List<Session> sessions() {
            return List.copyOf(sessions);
        }

        Replicator service() {
            return (Replicator) Proxy.newProxyInstance(classLoader(),
                    new Class<?>[] {Replicator.class}, (proxy, method, arguments) -> {
                        if (!"replicate".equals(method.getName())) {
                            return null;
                        }
                        if (offered.size() >= takes) {
                            throw new ReplicationException("the transport went away");
                        }
                        sessions.add((Session) arguments[0]);
                        offered.add((String) arguments[2]);
                        return null;
                    });
        }
    }

    private static ResourceResolver resolverWithSession() {
        return resolverAdapting((Session) Proxy.newProxyInstance(
                classLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> identityOrNothing(proxy, method)));
    }

    /**
     * What a stood-up interface answers: itself for the three questions every object is asked,
     * and nothing for everything else.
     *
     * <p>Two of them matter because a proxy that answered nothing to {@code hashCode} would fail
     * the moment anything put it in a collection, which is a failure about this test rather than
     * about what it is testing. Equality is refused loudly instead of answered: nothing here is
     * ever compared, and a double that quietly answered would be one somebody could put in a set
     * and be surprised by.</p>
     *
     * @param proxy the stood-up object
     * @param method what was called on it
     * @return the answer
     */
    private static Object identityOrNothing(Object proxy, java.lang.reflect.Method method) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> throw new UnsupportedOperationException(
                    "nothing compares these doubles, so this one has no answer to give");
            case "toString" -> "a stood-up " + method.getDeclaringClass().getSimpleName();
            default -> null;
        };
    }

    /**
     * The loader a stood-up interface is defined against.
     *
     * <p>The context loader rather than a class's own, because in a container the two are not the
     * same and the one that works is this one.</p>
     *
     * @return the loader
     */
    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    private static ResourceResolver resolverWithoutSession() {
        return resolverAdapting(null);
    }

    private static ResourceResolver resolverAdapting(Session session) {
        return (ResourceResolver) Proxy.newProxyInstance(
                classLoader(), new Class<?>[] {ResourceResolver.class},
                (proxy, method, arguments) -> "adaptTo".equals(method.getName())
                        && arguments != null && Session.class.equals(arguments[0])
                        ? session : identityOrNothing(proxy, method));
    }
}
