// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.StatePath;

/**
 * The acknowledgement's twelfth member, which is written for a refusal and for nothing else.
 *
 * <p>The client refuses an answer carrying {@code non_execution} beside an acceptance, a retirement,
 * or a physical job, so the member's presence is not a detail of formatting: an acknowledgement that
 * wrote it when nothing was refused is one the client cannot interpret at all. Both cases are driven
 * through the shipped document writer rather than through a re-implementation of it.</p>
 */
final class SubmissionResponseTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path REGISTRY_FIXTURES = REPOSITORY.resolve(
            "core/src/test/resources/fixtures/command-registry/accepted");

    private static final Path SUBMISSION_FIXTURE = REPOSITORY.resolve(
            "core/src/test/resources/fixtures/submit-servlet/a-submission.json");

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load(), "the contract did not authenticate").contract();

    @Test
    @DisplayName("an acceptance writes every member the client knows except the refusal one")
    void anAcceptanceWritesNoRefusalMember() {
        final DocumentValue.Mapping answered = documentOf(new SubmissionResponse.NoRefusal());
        assertEquals(SubmissionResponse.MEMBERS.stream()
                        .filter(member -> !SubmissionResponse.NON_EXECUTION.equals(member))
                        .sorted()
                        .toList(),
                answered.members().keySet().stream().sorted().toList(),
                "an acknowledgement naming no refusal wrote something the client does not know");
        assertEquals(java.util.Optional.empty(),
                answered.member(SubmissionResponse.NON_EXECUTION),
                "the refusal member was written for an answer that refused nothing");
    }

    @Test
    @DisplayName("a refusal writes the refusal member, and writes it as exactly the twelve")
    void aRefusalWritesTheRefusalMember() {
        final DocumentValue.Mapping answered =
                documentOf(SubmissionResponse.Refusal.CAPACITY);
        assertEquals(new DocumentValue.Text("capacity"),
                answered.member(SubmissionResponse.NON_EXECUTION).orElseThrow(),
                "a named refusal did not reach the member the client reads it from");
        assertEquals(SubmissionResponse.MEMBERS.stream().sorted().toList(),
                answered.members().keySet().stream().sorted().toList(),
                "a refusal was not written as exactly the members the client knows");
        assertEquals("semantic", SubmissionResponse.Refusal.SEMANTIC.spelling());
        assertEquals("capacity", SubmissionResponse.Refusal.CAPACITY.spelling());
    }

    private static DocumentValue.Mapping documentOf(SubmissionResponse.NonExecution refusal) {
        final SubmissionResponse accepted = SubmissionResponse.of(operation(), "a-subscription",
                1_000L, SubmissionResponse.Acceptance.THE_FIRST_TIME, List.of());
        final SubmissionResponse written = new SubmissionResponse(accepted.provenance(),
                accepted.revision(), accepted.generation(), accepted.identifier(),
                accepted.targetDigest(), accepted.submittedCommandDigest(), accepted.subscription(),
                accepted.retentionMilliseconds(), accepted.alreadyAccepted(),
                accepted.physicalJobIdentifiers(), accepted.retired(), refusal);
        return assertInstanceOf(DocumentValue.Mapping.class, written.document(),
                "the acknowledgement is not an object");
    }

    /**
     * One operation, built the way the dispatch builds one, so the acknowledgement's own values come
     * from a record rather than from values this suite chose.
     */
    private static LogicalOperation operation() {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REGISTRY_FIXTURES), "the fixture registry was refused").registry();
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SubmitServlet.OPERATION, operationDocument());
        final OperationIdentity identity = assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(new DocumentValue.Mapping(members).member(SubmitServlet.OPERATION)
                        .orElseThrow(), CONTRACT), "the operation was refused").identity();
        final CommandContractIdentity contract = assertInstanceOf(CommandContractIdentity.Held.class,
                registry.row("query_paths").orElseThrow().identity(
                        CommandContractIdentity.Bounds.from(CONTRACT))).identity();
        final StatePath.Caller caller = assertInstanceOf(StatePath.Held.class,
                StatePath.caller("admin")).caller();
        return assertInstanceOf(LogicalOperation.Held.class, LogicalOperation.accepted(identity,
                Digest.of("submission".getBytes(StandardCharsets.UTF_8)), contract, caller,
                1_000L, 1_000L, CONTRACT)).operation();
    }

    private static DocumentValue operationDocument() {
        return read(SUBMISSION_FIXTURE).member(SubmitServlet.OPERATION)
                .orElseThrow();
    }

    private static DocumentValue.Mapping read(Path file) {
        try {
            return assertInstanceOf(DocumentValue.Mapping.class,
                    assertInstanceOf(BoundedDocumentReader.Read.class,
                            BoundedDocumentReader.read(Files.readAllBytes(file),
                                    BoundedDocumentReader.Bounds.from(CONTRACT))).value(),
                    "the fixture is not an object");
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
