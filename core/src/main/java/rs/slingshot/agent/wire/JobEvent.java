// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One thing this agent says happened to one job.
 *
 * <p>An event belongs to one operation at one incarnation of the store. An event naming another
 * incarnation is an event about a different durable thing — the same operation identifier after a
 * rebuild is not the same operation — so it is refused naming both generations rather than read as
 * news about the one being served.</p>
 */
public final class JobEvent {

    /** The member the operation's own name is carried in. */
    public static final String IDENTIFIER = "agent_operation_identifier";

    /** The member the store's incarnation is carried in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The member the kind is carried in. */
    public static final String KIND = "kind";

    /** The member the sequence is carried in. */
    public static final String SEQUENCE = "sequence";

    /** Every member an event has, and there is no fifth. */
    public static final List<String> MEMBERS = List.of(GENERATION, IDENTIFIER, KIND, SEQUENCE);

    private final AgentOperationIdentifier identifier;
    private final EventStoreGeneration generation;
    private final JobEventKind kind;
    private final EventSequence sequence;

    private JobEvent(AgentOperationIdentifier identifier, EventStoreGeneration generation,
                     JobEventKind kind, EventSequence sequence) {
        this.identifier = identifier;
        this.generation = generation;
        this.kind = kind;
        this.sequence = sequence;
    }

    /** Why a document is not an event this store will hold. */
    public enum Refusal {
        /** The document is not an object with four members. */
        NOT_A_DOCUMENT,
        /** A member is missing, and an event is all four or none. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** A member is there and is not the kind of value it has to be. */
        WRONG_KIND_OF_VALUE,
        /** The kind is not one this build knows, and guessing about it is the failure. */
        UNKNOWN_KIND,
        /** The operation identifier is not the shape an identifier has. */
        IDENTIFIER_REFUSED,
        /** The generation or the sequence is outside the range its member permits. */
        OUT_OF_RANGE,
        /** The event names an incarnation of the store other than the one being served. */
        FOREIGN_GENERATION,
        /** One operation already holds as many events as it may. */
        TOO_MANY_EVENTS,
        /** One operation already holds as many event bytes as it may. */
        TOO_MANY_EVENT_BYTES
    }

    /** The result of reading one: the event, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that is an event about the operation and incarnation being served.
     *
     * @param event the event it carried
     */
    public record Held(JobEvent event) implements Outcome {
    }

    /**
     * A document that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed, naming both values where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * What one operation's run of events may come to.
     *
     * @param events how many events one operation may hold
     * @param bytes how many bytes those events may come to
     */
    public record Budget(long events, long bytes) {

        /**
         * The budget the contract declares, which is where both numbers live.
         *
         * @param contract the authenticated contract
         * @return the budget
         */
        public static Budget from(AgentContract contract) {
            return new Budget(contract.value(ContractLimit.MAXIMUM_OPERATION_EVENT_ROWS),
                    contract.value(ContractLimit.MAXIMUM_OPERATION_EVENT_BYTES));
        }

        /**
         * Whether one more event of a given size fits in what an operation has left.
         *
         * @param eventsHeld how many events this operation already holds
         * @param bytesHeld how many bytes those events already come to
         * @param theseBytes how large the event being added is
         * @return the one reason it does not fit, or nothing where it does
         */
        public Optional<Refused> admits(long eventsHeld, long bytesHeld, long theseBytes) {
            if (eventsHeld + 1 > events) {
                return Optional.of(new Refused(Refusal.TOO_MANY_EVENTS, "this operation holds "
                        + eventsHeld + " events, and may hold " + events));
            }
            if (bytesHeld + theseBytes > bytes) {
                return Optional.of(new Refused(Refusal.TOO_MANY_EVENT_BYTES, "this operation holds "
                        + bytesHeld + " event bytes and this event adds " + theseBytes
                        + ", past the bound of " + bytes));
            }
            return Optional.empty();
        }
    }

    /**
     * Reads an event, against the incarnation of the store currently being served.
     *
     * @param document the document
     * @param serving the incarnation this store is serving
     * @param contract the authenticated contract, which declares every bound
     * @return the event, or the one reason there is none
     */
    public static Outcome read(DocumentValue document, EventStoreGeneration serving,
                               AgentContract contract) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "a job document is an object with four members");
        }
        final Optional<Refused> shape = shapeOf(mapping);
        if (shape.isPresent()) {
            return shape.get();
        }
        final Optional<JobEventKind> kind = JobEventKind.named(text(mapping, KIND).orElse(""));
        if (kind.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_KIND, "this build knows no kind spelled that way,"
                    + " and reading around one is what waits forever or reports an outcome that has"
                    + " not happened");
        }
        return withKind(mapping, kind.get(), serving, contract);
    }

    private static Optional<Refused> shapeOf(DocumentValue.Mapping mapping) {
        final Optional<Refused> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .map(name -> new Refused(Refusal.MEMBER_UNKNOWN,
                        name + " is not a member a job document has"))
                .findFirst();
        if (unknown.isPresent()) {
            return unknown;
        }
        return MEMBERS.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .map(member -> new Refused(Refusal.MEMBER_ABSENT,
                        member + " is missing, and a job document is all four members or none"))
                .findFirst();
    }

    private static Outcome withKind(DocumentValue.Mapping mapping, JobEventKind kind,
                                    EventStoreGeneration serving, AgentContract contract) {
        final Optional<String> written = text(mapping, IDENTIFIER);
        if (written.isEmpty()) {
            return new Refused(Refusal.WRONG_KIND_OF_VALUE, IDENTIFIER + " is not text");
        }
        final AgentOperationIdentifier.Outcome named =
                AgentOperationIdentifier.of(written.get(), contract);
        if (named instanceof final AgentOperationIdentifier.Refused refused) {
            return new Refused(Refusal.IDENTIFIER_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        return withIdentifier(mapping, kind, ((AgentOperationIdentifier.Held) named).identifier(),
                serving);
    }

    private static Outcome withIdentifier(DocumentValue.Mapping mapping, JobEventKind kind,
                                          AgentOperationIdentifier identifier,
                                          EventStoreGeneration serving) {
        final Optional<Long> generation = whole(mapping, GENERATION);
        final Optional<Long> sequence = whole(mapping, SEQUENCE);
        if (generation.isEmpty() || sequence.isEmpty()) {
            return new Refused(Refusal.WRONG_KIND_OF_VALUE,
                    "the generation and the sequence are whole numbers");
        }
        final EventStoreGeneration.Outcome held = EventStoreGeneration.of(generation.get());
        final EventSequence.Outcome counted = EventSequence.of(sequence.get());
        if (held instanceof final EventStoreGeneration.Refused refused) {
            return new Refused(Refusal.OUT_OF_RANGE, refused.refusal() + ": " + refused.detail());
        }
        if (counted instanceof final EventSequence.Refused refused) {
            return new Refused(Refusal.OUT_OF_RANGE, refused.refusal() + ": " + refused.detail());
        }
        return serving(((EventStoreGeneration.Held) held).generation(),
                ((EventSequence.Held) counted).sequence(), kind, identifier, serving);
    }

    private static Outcome serving(EventStoreGeneration generation, EventSequence sequence,
                                   JobEventKind kind, AgentOperationIdentifier identifier,
                                   EventStoreGeneration serving) {
        if (!generation.equals(serving)) {
            return new Refused(Refusal.FOREIGN_GENERATION, "this document names generation "
                    + generation + " and this store is serving " + serving);
        }
        return new Held(new JobEvent(identifier, generation, kind, sequence));
    }

    private static Optional<String> text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
    }

    private static Optional<Long> whole(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Whole.class::isInstance)
                .map(value -> ((DocumentValue.Whole) value).value());
    }

    /**
     * The operation this event is about.
     *
     * @return the identifier
     */
    public AgentOperationIdentifier identifier() {
        return identifier;
    }

    /**
     * The incarnation of the store this event belongs to.
     *
     * @return the generation
     */
    public EventStoreGeneration generation() {
        return generation;
    }

    /**
     * What happened.
     *
     * @return the kind
     */
    public JobEventKind kind() {
        return kind;
    }

    /**
     * Where this event sits in the run of events about its operation.
     *
     * @return the sequence
     */
    public EventSequence sequence() {
        return sequence;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final JobEvent event && identifier.equals(event.identifier)
                && generation.equals(event.generation) && kind == event.kind
                && sequence.equals(event.sequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, generation, kind, sequence);
    }

    @Override
    public String toString() {
        return kind.spelling() + " " + sequence + " of " + identifier;
    }
}
