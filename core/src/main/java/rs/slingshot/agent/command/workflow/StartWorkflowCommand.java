// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which workflow to start, on what, and what to record about it.
 *
 * <p>This is the most powerful command on this whole surface, and it does not look like it. A
 * workflow is work the platform carries out afterwards under its own identity, which is very often
 * more privileged than the caller's — so a caller who cannot change a page but can start a workflow
 * whose steps change it has changed it, indirectly, and nothing in the audit trail says they did.
 * The payload is therefore checked against what the caller themselves may change, before the
 * platform is asked, and that check is the reason this command is safe to offer at all.</p>
 *
 * <p>The metadata is text keyed by name and nothing else. Workflow metadata is read by steps that
 * were written by somebody else entirely, and a structured value here would be a way to send that
 * somebody a document they did not expect.</p>
 *
 * @param modelIdentifier which workflow model to run
 * @param payloadPath what to run it on
 * @param title what to call this instance, which may be {@link #NO_TITLE}
 * @param comment what to record about why, which may be {@link #NO_COMMENT}
 * @param metadata what else to record on it, by name
 */
public record StartWorkflowCommand(String modelIdentifier, String payloadPath, String title,
                                   String comment, SequencedMap<String, String> metadata) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "start_workflow";

    /** The member the model's identifier is carried in. */
    public static final String MODEL_IDENTIFIER = "model_identifier";

    /** The member the payload's address is carried in. */
    public static final String PAYLOAD_PATH = "payload_path";

    /** The member the instance's title is carried in. */
    public static final String TITLE = "title";

    /** The member the comment is carried in. */
    public static final String COMMENT = "comment";

    /** The member the metadata is carried in. */
    public static final String METADATA = "metadata";

    /** What the title says when the caller named none. */
    public static final String NO_TITLE = "";

    /** What the comment says when the caller wrote none. */
    public static final String NO_COMMENT = "";

    /** Every member this command's argument has, and there is no sixth. */
    public static final List<String> MEMBERS =
            List.of(COMMENT, METADATA, MODEL_IDENTIFIER, PAYLOAD_PATH, TITLE);

    /** The members a caller has to send; this command chooses neither the model nor the payload. */
    public static final List<String> REQUIRED = List.of(MODEL_IDENTIFIER, PAYLOAD_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The model's identifier is empty, or longer than one may be. */
        MODEL_REJECTED,
        /** The payload is not an absolute repository path. */
        PAYLOAD_REJECTED,
        /** The title or the comment is longer than the contract allows. */
        TEXT_TOO_LONG,
        /** The metadata is not text by name, or there is more of it than the contract allows. */
        METADATA_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(StartWorkflowCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the entry rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** Holds a start whose metadata nothing can change afterwards. */
    public StartWorkflowCommand {
        metadata = new LinkedHashMap<>(metadata);
    }

    /**
     * What to record on the instance.
     *
     * @return the metadata, which nothing may add to
     */
    @Override
    public SequencedMap<String, String> metadata() {
        return Collections.unmodifiableSequencedMap(metadata);
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every member
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which workflow to run and on what");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final Optional<String> absent = REQUIRED.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; a workflow is"
                    + " work the platform carries out afterwards, so neither which work nor what"
                    + " it runs on is something this side may choose");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final long modelBound =
                contract.value(ContractLimit.MAXIMUM_WORKFLOW_MODEL_IDENTIFIER_BYTES);
        if (!(mapping.member(MODEL_IDENTIFIER).orElseThrow()
                instanceof final DocumentValue.Text model)
                || model.value().isEmpty() || model.value().length() > modelBound) {
            return new Refused(Refusal.MODEL_REJECTED, MODEL_IDENTIFIER + " is what the platform"
                    + " calls one workflow model: not empty, and within the " + modelBound
                    + " an identifier may be");
        }
        final long pathBound = contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES);
        if (!(mapping.member(PAYLOAD_PATH).orElseThrow()
                instanceof final DocumentValue.Text payload)
                || payload.value().isEmpty() || payload.value().charAt(0) != '/'
                || payload.value().length() > pathBound) {
            return new Refused(Refusal.PAYLOAD_REJECTED,
                    PAYLOAD_PATH + " is an absolute path beginning at the root");
        }
        return described(model.value(), payload.value(), mapping, contract);
    }

    private static Outcome described(String model, String payload, DocumentValue.Mapping mapping,
                                     AgentContract contract) {
        final Optional<String> title = text(mapping, TITLE,
                contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES));
        final Optional<String> comment = text(mapping, COMMENT,
                contract.value(ContractLimit.MAXIMUM_WORKFLOW_COMMENT_BYTES));
        if (title.isEmpty() || comment.isEmpty()) {
            return new Refused(Refusal.TEXT_TOO_LONG, TITLE + " and " + COMMENT + " are text,"
                    + " within the " + contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES)
                    + " and " + contract.value(ContractLimit.MAXIMUM_WORKFLOW_COMMENT_BYTES)
                    + " each may be");
        }
        return metadataIn(mapping, contract)
                .<Outcome>map(metadata -> new Held(new StartWorkflowCommand(model, payload,
                        title.orElseThrow(), comment.orElseThrow(), metadata)))
                .orElseGet(() -> new Refused(Refusal.METADATA_REJECTED, METADATA + " is text by"
                        + " name and nothing else, within the "
                        + contract.value(ContractLimit.MAXIMUM_WORKFLOW_METADATA_ENTRIES)
                        + " entries one instance records. Workflow metadata is read by steps"
                        + " somebody else wrote, and a structured value here would be a way to"
                        + " send them a document they did not expect."));
    }

    private static Optional<String> text(DocumentValue.Mapping mapping, String member, long bound) {
        final Optional<DocumentValue> asked = mapping.member(member);
        if (asked.isEmpty()) {
            return Optional.of("");
        }
        return asked.orElseThrow() instanceof final DocumentValue.Text held
                && held.value().length() <= bound
                ? Optional.of(held.value()) : Optional.empty();
    }

    private static Optional<SequencedMap<String, String>> metadataIn(DocumentValue.Mapping mapping,
                                                                     AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(METADATA);
        if (asked.isEmpty()) {
            return Optional.of(new LinkedHashMap<>());
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Mapping entries)
                || entries.members().size()
                        > contract.value(ContractLimit.MAXIMUM_WORKFLOW_METADATA_ENTRIES)) {
            return Optional.empty();
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES);
        final SequencedMap<String, String> held = new LinkedHashMap<>();
        for (final var entry : entries.members().entrySet()) {
            if (!(entry.getValue() instanceof final DocumentValue.Text value)
                    || value.value().length() > bound) {
                return Optional.empty();
            }
            held.put(entry.getKey(), value.value());
        }
        return Optional.of(held);
    }
}
