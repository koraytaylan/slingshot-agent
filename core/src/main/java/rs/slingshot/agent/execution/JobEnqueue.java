// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.identity.OperationIdentity;

/**
 * Handing one deferred operation to the job system, after it is already durable.
 *
 * <p>The order is the point. Every durable fact is written and committed before a job exists, so a
 * store that holds the record and no job is a store recovery can move, and a job with no record is
 * a job this side drops. The other order — enqueue, then write — is the one where a client is told
 * yes about work nothing remembers.</p>
 *
 * <p>An immediate row never reaches a queue at all: it is executed by the route that admitted it.
 * A submission whose manifest declares intake slots is not enqueued until every slot is complete,
 * because a command that started against a payload still arriving would be a command reading half
 * a file.</p>
 */
public final class JobEnqueue {

    private JobEnqueue() {
    }

    /** Why an operation is not handed to the job system. */
    public enum Refusal {
        /** It is an immediate row, which is executed by the route that admitted it. */
        NOT_DEFERRED,
        /** Nothing durable holds it yet, and a job with no record is a job this side drops. */
        NOT_RECORDED,
        /** Its manifest declared payloads that have not all arrived. */
        INTAKE_OUTSTANDING
    }

    /** The result of handing one over: the job's own properties, or the one reason there are none. */
    public sealed interface Outcome permits Enqueued, Refused {
    }

    /**
     * An operation the job system may now be told about.
     *
     * @param topic the topic to send it on
     * @param properties what the job carries, which is everything needed to find the record again
     */
    public record Enqueued(String topic, Map<String, Object> properties) implements Outcome {

        /** Holds properties nothing can change afterwards. */
        public Enqueued {
            properties = Map.copyOf(properties);
        }
    }

    /**
     * An operation it may not.
     *
     * @param refusal why it may not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether one operation may be handed to the job system, and what the job would carry.
     *
     * @param session the session to read under
     * @param identity which operation
     * @param executionClass whether the command runs in its caller's request or later
     * @param intakeOutstanding how many declared payloads have not arrived
     * @return the job's properties, or the one reason there are none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome of(Session session, OperationIdentity identity,
                             CommandJobTopic.ExecutionClass executionClass, long intakeOutstanding)
            throws RepositoryException {
        if (executionClass != CommandJobTopic.ExecutionClass.DEFERRED) {
            return new Refused(Refusal.NOT_DEFERRED, "an immediate command is executed by the route"
                    + " that admitted it and never reaches a queue");
        }
        final OperationStore.Outcome record = OperationStore.read(session, identity);
        if (record instanceof OperationStore.Refused) {
            return new Refused(Refusal.NOT_RECORDED, "nothing durable holds "
                    + identity.identifier() + ", and a job with no record is a job this side drops");
        }
        if (intakeOutstanding > 0) {
            return new Refused(Refusal.INTAKE_OUTSTANDING, intakeOutstanding + " declared payloads"
                    + " have not arrived, and a command that started now would read half a file");
        }
        return new Enqueued(CommandJobTopic.TOPIC, properties(identity));
    }

    /**
     * What a job carries, which is everything needed to find the record again and nothing else.
     *
     * @param identity which operation
     * @return the properties
     */
    public static Map<String, Object> properties(OperationIdentity identity) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(CommandJobTopic.IDENTIFIER, identity.identifier().rendered());
        properties.put(CommandJobTopic.GENERATION, identity.generation().number());
        properties.put(CommandJobTopic.TARGET_DIGEST, identity.targetDigest().rendered());
        properties.put(CommandJobTopic.ENVIRONMENT_REVISION, identity.environmentRevision());
        properties.put(CommandJobTopic.EXECUTION_CLASS,
                CommandJobTopic.ExecutionClass.DEFERRED.spelling());
        return properties;
    }

    /**
     * The one reason an operation was not handed over, where it was not.
     *
     * @param outcome what handing it over produced
     * @return the refusal, or nothing where a job may be sent
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
