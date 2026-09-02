// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionRecord;

/**
 * One stream: whose subscription it is, which incarnation it follows, and which operation it is
 * about.
 *
 * <p>Filtered to one operation before anything is read, so no event for another operation can be
 * reached however the request is shaped. That is a property of what a session is rather than of a
 * check somewhere in the writing, which is what makes it hold for every ending and every
 * resumption.</p>
 *
 * @param subscription whose stream this is
 * @param generation the incarnation it follows
 * @param operation the operation it is about
 * @param caller who is following it
 */
public record StreamSession(SubscriptionRecord.Identifier subscription,
                            EventStoreGeneration generation, StatePath operation,
                            StatePath.Caller caller) {

    /** Why a stream is not opened at all. */
    public enum Refusal {
        /** The subscription named is not one this side holds. */
        UNKNOWN_SUBSCRIPTION,
        /** The incarnation named is not the one this store serves. */
        FOREIGN_GENERATION,
        /** Nothing here holds the operation, or it is not this caller's to follow. */
        NOT_THIS_CALLERS_OPERATION,
        /** The request names no subscription or no operation this build reads. */
        NOT_READABLE
    }

    /** The result of opening one: the session, or the reason no stream was opened. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A session a stream may be written from.
     *
     * @param session the session
     */
    public record Held(StreamSession session) implements Outcome {
    }

    /**
     * No session, and why.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * What a request asked to follow.
     *
     * @param subscription the subscription it named
     * @param operation the operation it named
     * @param generation the incarnation it named, or nothing where it named none
     * @param caller who is asking
     */
    public record Asked(String subscription, String operation, long generation,
                        StatePath.Caller caller) {
    }

    /**
     * Whether a request may have a stream at all, decided before one is opened.
     *
     * <p>Every refusal here is an ordinary response rather than a stream error: a client that never
     * got a stream should not have to parse one to find out why.</p>
     *
     * @param store the caller's own session
     * @param asked what the request asked to follow
     * @param contract the authenticated contract, which declares every bound
     * @return the session, or the one reason there is no stream
     * @throws RepositoryException if the repository fails
     */
    public static Outcome of(Session store, Asked asked, AgentContract contract)
            throws RepositoryException {
        final SubscriptionRecord.Outcome named =
                SubscriptionRecord.identifier(asked.subscription(), contract);
        final Optional<AgentOperationIdentifier> operation = operationIn(asked, contract);
        if (!(named instanceof final SubscriptionRecord.Held subscription)
                || operation.isEmpty()) {
            return new Refused(Refusal.NOT_READABLE,
                    "a stream names a subscription and an operation this build reads");
        }
        final EventStoreGeneration serving = serving(store);
        if (asked.generation() > 0 && asked.generation() != serving.number()) {
            return new Refused(Refusal.FOREIGN_GENERATION, "this store serves generation "
                    + serving.number() + " and this stream names " + asked.generation());
        }
        if (!store.nodeExists(SubscriptionRecord.pathOf(subscription.identifier()).path())) {
            return new Refused(Refusal.UNKNOWN_SUBSCRIPTION,
                    "this side holds no such subscription, and a stream on one nobody took would"
                            + " deliver events nobody is accounting for");
        }
        return owned(store, asked, subscription.identifier(), serving, operation.get());
    }

    private static Outcome owned(Session store, Asked asked,
                                 SubscriptionRecord.Identifier subscription,
                                 EventStoreGeneration serving,
                                 AgentOperationIdentifier operation) throws RepositoryException {
        final StatePath path = StatePath.operation(serving, operation);
        if (!store.nodeExists(path.path())) {
            // Nothing here holds it, or the caller's own session cannot see it: one answer, because
            // a caller who could tell those apart could ask which identifiers exist.
            return new Refused(Refusal.NOT_THIS_CALLERS_OPERATION,
                    "nothing this caller can see holds that operation");
        }
        return new Held(new StreamSession(subscription, serving, path, asked.caller()));
    }

    private static Optional<AgentOperationIdentifier> operationIn(Asked asked,
                                                                  AgentContract contract) {
        final AgentOperationIdentifier.Outcome held =
                AgentOperationIdentifier.of(asked.operation(), contract);
        return held instanceof final AgentOperationIdentifier.Held operation
                ? Optional.of(operation.identifier())
                : Optional.empty();
    }

    private static EventStoreGeneration serving(Session store) throws RepositoryException {
        final GenerationStore.Outcome held = GenerationStore.serving(store);
        return held instanceof final GenerationStore.Held serving
                ? serving.generation()
                : ((EventStoreGeneration.Held) EventStoreGeneration
                        .of(EventStoreGeneration.FIRST)).generation();
    }

    /**
     * The one reason there is no stream, where there is none.
     *
     * @param outcome what opening it produced
     * @return the refusal, or nothing where there is a session
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
