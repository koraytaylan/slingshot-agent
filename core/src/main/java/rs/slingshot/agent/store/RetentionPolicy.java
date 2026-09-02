// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Arrays;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * How long each kind of stored thing is kept, measured from where the client measures it.
 *
 * <p>A client budgets against a window it was told about and anchors that window at the moment it
 * made its request. Anchoring it at the moment the agent got round to writing something — or worse,
 * at the moment somebody asks how long is left — silently lengthens it. That sounds generous and is
 * not: the client's arithmetic about when it may stop holding on to an identifier is then wrong,
 * and it is wrong in the direction where it gives up too early.</p>
 *
 * <p>Every minimum is the contract's. A deployment may configure a longer retention and may not
 * configure a shorter one, because a shorter one is this side quietly withdrawing something the
 * client was promised; and a configured retention past what this side will persist is refused
 * rather than clamped, so a number nobody can honour is never advertised.</p>
 */
public final class RetentionPolicy {

    /**
     * The property a record's own request-start is written in.
     *
     * <p>Spelled here as well as where a record is written, because a retention is read from a
     * record by a part of this build that does not otherwise know what a record is. That the two
     * spellings are the same is asserted rather than assumed.</p>
     */
    public static final String REQUEST_START = "request_start_unix_milliseconds";

    private RetentionPolicy() {
    }

    /** Each kind of stored thing this agent keeps, with the minimum the contract declares for it. */
    public enum Kind {

        /** The answer a command produced, kept so a client that asked again can be given it. */
        RESULT("result", ContractLimit.MINIMUM_RESULT_RETENTION_MILLISECONDS),

        /** What is currently true about an operation. */
        SNAPSHOT("snapshot", ContractLimit.MINIMUM_SNAPSHOT_RETENTION_MILLISECONDS),

        /** The operation record itself, which is what a lookup finds. */
        OPERATION_DETAIL("operation_detail",
                ContractLimit.MINIMUM_OPERATION_DETAIL_RETENTION_MILLISECONDS),

        /** Bytes published as an answer too large to carry. */
        ARTIFACT("artifact", ContractLimit.MINIMUM_ARTIFACT_RETENTION_MILLISECONDS);

        private final String spelling;

        private final ContractLimit minimum;

        Kind(String spelling, ContractLimit minimum) {
            this.spelling = spelling;
            this.minimum = minimum;
        }

        /**
         * How this kind is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The shortest this kind may be kept for, which the contract declares and this build does
         * not.
         *
         * @param contract the authenticated contract
         * @return the minimum
         */
        public long minimum(AgentContract contract) {
            return contract.value(minimum);
        }

        /**
         * The kind one spelling names.
         *
         * @param spelling the spelling
         * @return the kind, or nothing where this build keeps no such thing
         */
        public static Optional<Kind> named(String spelling) {
            return Arrays.stream(values())
                    .filter(kind -> kind.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /** Why a retention is not one this side will keep to. */
    public enum Refusal {
        /** It is shorter than the contract's minimum for its kind. */
        BELOW_THE_MINIMUM,
        /** It is longer than this side will persist, so it could never be honoured. */
        PAST_THE_PERSISTED_MAXIMUM,
        /** There is no record to measure from. */
        NO_RECORD
    }

    /** The result of deriving a retention: one this side keeps to, or why there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A retention this side keeps to.
     *
     * @param retainedUntil when what it covers stops being kept
     */
    public record Held(RetainedUntil retainedUntil) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why not
     * @param detail what was observed, naming both values where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * When one kind of one record's belongings stop being kept.
     *
     * @param session the session to read under
     * @param operation the operation's own record
     * @param kind which kind of stored thing
     * @param contract the authenticated contract, which declares every minimum
     * @return the instant, or the one reason there is none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome until(Session session, StatePath operation, Kind kind,
                                AgentContract contract) throws RepositoryException {
        if (!session.nodeExists(operation.path())) {
            return new Refused(Refusal.NO_RECORD, "there is no record at " + operation.path()
                    + " to measure a retention from");
        }
        final Node record = session.getNode(operation.path());
        if (!record.hasProperty(REQUEST_START)) {
            return new Refused(Refusal.NO_RECORD, operation.path() + " carries no request start,"
                    + " and a retention measured from anything else is a longer one");
        }
        return new Held(RetainedUntil.from(kind,
                record.getProperty(REQUEST_START).getLong() + kind.minimum(contract)));
    }

    /**
     * When one kind of a record's belongings stop being kept under a configured retention.
     *
     * @param session the session to read under
     * @param operation the operation's own record
     * @param configured the configured retention, in milliseconds
     * @param contract the authenticated contract, which declares the minimum and the maximum
     * @return the instant, or the one reason there is none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome until(Session session, StatePath operation, Configured configured,
                                AgentContract contract) throws RepositoryException {
        final Optional<Refused> refused = refusalFor(configured, contract);
        if (refused.isPresent()) {
            return refused.get();
        }
        final Outcome minimum = until(session, operation, configured.kind(), contract);
        if (minimum instanceof Refused) {
            return minimum;
        }
        return new Held(RetainedUntil.from(configured.kind(),
                ((Held) minimum).retainedUntil().instantUnixMilliseconds()
                        - configured.kind().minimum(contract) + configured.milliseconds()));
    }

    /**
     * One deployment's configured retention for one kind.
     *
     * @param kind which kind of stored thing
     * @param milliseconds how long the deployment says it keeps that kind
     */
    public record Configured(Kind kind, long milliseconds) {
    }

    /**
     * What this side advertises as the window for one kind, where it may advertise it at all.
     *
     * @param configured the configured retention
     * @param contract the authenticated contract, which declares the minimum and the maximum
     * @return the milliseconds to advertise, or the one reason there is nothing to advertise
     */
    public static Outcome advertised(Configured configured, AgentContract contract) {
        final Optional<Refused> refused = refusalFor(configured, contract);
        return refused.isPresent()
                ? refused.get()
                : new Held(RetainedUntil.from(configured.kind(), configured.milliseconds()));
    }

    private static Optional<Refused> refusalFor(Configured configured, AgentContract contract) {
        final long minimum = configured.kind().minimum(contract);
        if (configured.milliseconds() < minimum) {
            return Optional.of(new Refused(Refusal.BELOW_THE_MINIMUM, configured.kind().spelling()
                    + " is configured at " + configured.milliseconds() + " and the contract's"
                    + " minimum for it is " + minimum));
        }
        final long maximum =
                contract.value(ContractLimit.MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS);
        if (configured.milliseconds() > maximum) {
            return Optional.of(new Refused(Refusal.PAST_THE_PERSISTED_MAXIMUM,
                    configured.kind().spelling() + " is configured at " + configured.milliseconds()
                            + " and this side will persist at most " + maximum));
        }
        return Optional.empty();
    }

    /**
     * The one reason there is no retention, where there is none.
     *
     * @param outcome what deriving it produced
     * @return the refusal, or nothing where there is a retention
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
