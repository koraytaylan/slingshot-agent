// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What this agent says when it has taken a submission, in exactly the words the client reads.
 *
 * <p>Every member here is one the client's own build knows, and there is no tenth. That is not
 * tidiness: the client treats an answer carrying a field it does not know as an answer it cannot
 * interpret, and an uninterpretable answer to a submission is the worst outcome in the whole
 * protocol — the client neither knows the work started nor knows it did not.</p>
 *
 * <p>Every value is the record's rather than the request's. An acknowledgement echoing what a caller
 * sent would agree with the caller whatever this side actually wrote down, and the client is built
 * to compare exactly these four values against what it sent precisely so that a disagreement is
 * visible. Reading them from the record is what makes that comparison mean something.</p>
 *
 * @param generation the incarnation the record is under
 * @param identifier the operation the record is
 * @param targetDigest the target the record is against
 * @param submittedCommandDigest the digest this side derived and wrote down
 * @param subscription the subscription this submission registered
 * @param retentionMilliseconds how long this side promises to keep what it produced
 * @param alreadyAccepted whether this side already held this submission before it arrived again
 * @param retired whether this side held it once and no longer does
 * @param physicalJobIdentifiers the physical jobs doing this work, which is none for a command that
 *     ran inside its own request
 */
public record SubmissionResponse(long generation, String identifier, String targetDigest,
                                 String submittedCommandDigest, String subscription,
                                 long retentionMilliseconds, Acceptance alreadyAccepted,
                                 Retirement retired, List<String> physicalJobIdentifiers) {

    /** The member the incarnation is carried in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The member the operation's own name is carried in. */
    public static final String IDENTIFIER = "agent_operation_identifier";

    /** The member the target is carried in. */
    public static final String TARGET_DIGEST = "author_target_identity_digest";

    /** The member the derived digest is carried in. */
    public static final String SUBMITTED_DIGEST = "submitted_command_digest";

    /** The member the subscription is carried in. */
    public static final String SUBSCRIPTION = "daemon_subscription_identifier";

    /** The member the promised retention is carried in. */
    public static final String RETENTION = "granted_retention_milliseconds";

    /** The member saying whether this side already held the submission. */
    public static final String ALREADY_ACCEPTED = "already_accepted";

    /** The member saying whether this side held it once and no longer does. */
    public static final String RETIRED = "retired";

    /** The member the physical jobs are carried in. */
    public static final String PHYSICAL_JOBS = "physical_sling_job_identifiers";

    /** Every member an acknowledgement carries, and there is no tenth. */
    public static final List<String> MEMBERS = List.of(GENERATION, IDENTIFIER, TARGET_DIGEST,
            SUBMITTED_DIGEST, SUBSCRIPTION, RETENTION, ALREADY_ACCEPTED, RETIRED, PHYSICAL_JOBS);

    /** Whether this side already held this submission when it arrived. */
    public enum Acceptance {
        /** It had not: this is the first time. */
        THE_FIRST_TIME,
        /** It had: this is a resend of work already recorded. */
        ALREADY_HELD
    }

    /** Whether this side held a submission once and no longer does. */
    public enum Retirement {
        /** It still holds it. */
        STILL_HELD,
        /** It held it and has since swept it. */
        NO_LONGER_HELD
    }

    /** Holds an acknowledgement whose job list nothing can change afterwards. */
    public SubmissionResponse {
        physicalJobIdentifiers = List.copyOf(physicalJobIdentifiers);
    }

    /**
     * The acknowledgement one record produces, taken from the record and nothing else.
     *
     * @param operation the record as this side wrote it
     * @param subscription the subscription the submission registered
     * @param retentionMilliseconds how long this side promises to keep what it produced
     * @param acceptance whether this side already held it
     * @return the acknowledgement
     */
    public static SubmissionResponse of(LogicalOperation operation, String subscription,
                                        long retentionMilliseconds, Acceptance acceptance) {
        return new SubmissionResponse(operation.identity().generation().number(),
                operation.identity().identifier().rendered(),
                operation.identity().targetDigest().rendered(),
                operation.submissionDigest().rendered(),
                subscription,
                retentionMilliseconds,
                acceptance,
                Retirement.STILL_HELD,
                List.of());
    }

    /**
     * This acknowledgement as a document.
     *
     * @return the document, whose members are the nine and no others
     */
    public DocumentValue document() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(GENERATION, new DocumentValue.Whole(generation));
        members.put(IDENTIFIER, new DocumentValue.Text(identifier));
        members.put(TARGET_DIGEST, new DocumentValue.Text(targetDigest));
        members.put(SUBMITTED_DIGEST, new DocumentValue.Text(submittedCommandDigest));
        members.put(SUBSCRIPTION, new DocumentValue.Text(subscription));
        members.put(RETENTION, new DocumentValue.Whole(retentionMilliseconds));
        members.put(ALREADY_ACCEPTED, new DocumentValue.Flag(
                alreadyAccepted == Acceptance.ALREADY_HELD
                        ? DocumentValue.Truth.TRUE
                        : DocumentValue.Truth.FALSE));
        members.put(RETIRED, new DocumentValue.Flag(retired == Retirement.NO_LONGER_HELD
                ? DocumentValue.Truth.TRUE
                : DocumentValue.Truth.FALSE));
        members.put(PHYSICAL_JOBS, new DocumentValue.Sequence(physicalJobIdentifiers.stream()
                .map(DocumentValue.Text::new)
                .map(DocumentValue.class::cast)
                .toList()));
        return new DocumentValue.Mapping(members);
    }

    /**
     * This acknowledgement as the bytes it is answered with.
     *
     * @return the canonical bytes, or nothing where this build cannot write them
     */
    public Optional<String> rendered() {
        final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(document());
        return written instanceof final CanonicalByteWriter.Written bytes
                ? Optional.of(bytes.rendered())
                : Optional.empty();
    }
}
