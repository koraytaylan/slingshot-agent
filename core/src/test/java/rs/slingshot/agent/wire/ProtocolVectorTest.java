// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.asset.CreateAssetCommand;
import rs.slingshot.agent.command.asset.CreateAssetFolderCommand;
import rs.slingshot.agent.command.asset.DeleteAssetCommand;
import rs.slingshot.agent.command.asset.MoveAssetCommand;
import rs.slingshot.agent.command.asset.UpdateAssetMetadataCommand;
import rs.slingshot.agent.command.component.AddComponentCommand;
import rs.slingshot.agent.command.component.ComponentPathCommand;
import rs.slingshot.agent.command.component.DeleteComponentCommand;
import rs.slingshot.agent.command.component.ReorderComponentCommand;
import rs.slingshot.agent.command.component.UpdateComponentCommand;
import rs.slingshot.agent.command.configuration.ConfigurationIdentifierCommand;
import rs.slingshot.agent.command.configuration.FindConfigurationsCommand;
import rs.slingshot.agent.command.configuration.UpdateConfigurationCommand;
import rs.slingshot.agent.command.content.DownloadContentPackageCommand;
import rs.slingshot.agent.command.content.FindAssetsByMetadataCommand;
import rs.slingshot.agent.command.content.FindAssetsReferencedByPageCommand;
import rs.slingshot.agent.command.content.FindPagesByTemplateCommand;
import rs.slingshot.agent.command.content.FindPagesContainingPhraseCommand;
import rs.slingshot.agent.command.content.FindPagesUsingComponentsCommand;
import rs.slingshot.agent.command.content.ListAssetRenditionsCommand;
import rs.slingshot.agent.command.content.ListChildPagesCommand;
import rs.slingshot.agent.command.content.ListResourceMappingsCommand;
import rs.slingshot.agent.command.content.LoadContentCommand;
import rs.slingshot.agent.command.content.MapResourcePathCommand;
import rs.slingshot.agent.command.content.QueryPathsCommand;
import rs.slingshot.agent.command.content.ReadContentFragmentCommand;
import rs.slingshot.agent.command.content.ResolveResourcePathCommand;
import rs.slingshot.agent.command.fragment.CreateContentFragmentCommand;
import rs.slingshot.agent.command.fragment.CreateExperienceFragmentCommand;
import rs.slingshot.agent.command.fragment.FragmentDeletion;
import rs.slingshot.agent.command.fragment.UpdateContentFragmentCommand;
import rs.slingshot.agent.command.fragment.UpdateExperienceFragmentCommand;
import rs.slingshot.agent.command.framework.ListBundlesCommand;
import rs.slingshot.agent.command.framework.ListComponentsCommand;
import rs.slingshot.agent.command.framework.SetBundleStateCommand;
import rs.slingshot.agent.command.job.JobCommands;
import rs.slingshot.agent.command.mutation.MoveRequest;
import rs.slingshot.agent.command.page.CreatePageCommand;
import rs.slingshot.agent.command.page.DeletePageCommand;
import rs.slingshot.agent.command.page.MovePageCommand;
import rs.slingshot.agent.command.page.UpdatePageCommand;
import rs.slingshot.agent.command.principal.PrincipalCommands;
import rs.slingshot.agent.command.replication.AgentCommands;
import rs.slingshot.agent.command.replication.ReplicateContentCommand;
import rs.slingshot.agent.command.workflow.FindWorkflowInstancesCommand;
import rs.slingshot.agent.command.workflow.ListWorkflowModelsCommand;
import rs.slingshot.agent.command.workflow.StartWorkflowCommand;
import rs.slingshot.agent.command.workflow.WorkflowInstanceCommand;
import rs.slingshot.agent.continuation.ContinuationState;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.discovery.CapabilityDocument;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.DocumentProvenance;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Every document kind, proved against the committed vectors from both sides.
 *
 * <p>A vector is what makes a disagreement between two implementations a failing test in whichever
 * one changed, rather than a refused submission in production two weeks later. The client's own
 * vectors are carried in unchanged and run beside this side's; a copy with one byte altered is run
 * too, and has to fail, because vectors that were regenerated to match whatever this build produces
 * would agree with anything.</p>
 */
final class ProtocolVectorTest {

    private static final Path REPOSITORY = JobEventTest.repositoryRoot();

    private static final Path VECTORS = REPOSITORY.resolve("schemas/agent-protocol-vectors.json");

    private static final Path CLIENT_DIGEST_VECTORS =
            REPOSITORY.resolve("schemas/command-schema-digest-vectors.json");

    private static final AgentContract CONTRACT = JobEventTest.contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    private static final EventStoreGeneration SERVING = JobEventTest.generation(1);

    /** How many document kinds carry vectors: twelve protocol documents and fourteen commands. */
    private static final int SEVENTYSIX_KINDS = 76;

    @Test
    @DisplayName("every vector is accepted or refused exactly as it declares")
    void everyVectorIsReadAsItDeclares() {
        final List<DocumentValue.Mapping> vectors = vectors();
        assertTrue(vectors.size() >= 34, "the committed vector set lost rows: " + vectors.size());
        vectors.forEach(vector -> {
            final String note = text(vector, "note");
            final boolean accepted = accepts(text(vector, "kind"), text(vector, "input"));
            assertEquals(flag(vector, "accepted"), accepted,
                    "the vector proving " + note + " was " + (accepted ? "accepted" : "refused")
                            + " and declares the opposite");
        });
    }

    @Test
    @DisplayName("every accepted vector's input is already the canonical bytes it declares")
    void everyAcceptedVectorIsCanonical() {
        vectors().stream()
                .filter(vector -> flag(vector, "accepted"))
                .forEach(vector -> assertEquals(text(vector, "expected"),
                        canonical(text(vector, "input")),
                        "the vector proving " + text(vector, "note") + " is not the bytes it"
                                + " declares"));
    }

    @Test
    @DisplayName("a vector whose expected bytes differ by one character fails, naming it")
    void aVectorDifferingByOneCharacterFails() {
        final DocumentValue.Mapping accepted = vectors().stream()
                .filter(vector -> flag(vector, "accepted"))
                .findFirst()
                .orElseThrow();
        final String altered = text(accepted, "expected").replaceFirst("a", "b");
        assertFalse(altered.equals(canonical(text(accepted, "input"))),
                "the vector proving " + text(accepted, "note") + " agreed with altered bytes");
    }

    @Test
    @DisplayName("every document kind has a vector this build accepts and one it refuses")
    void everyKindIsCoveredFromBothSides() {
        final Set<String> kinds = vectors().stream()
                .map(vector -> text(vector, "kind"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(SEVENTYSIX_KINDS, kinds.size(), "a document kind lost its vectors: " + kinds);
        kinds.forEach(kind -> {
            assertTrue(vectors().stream().anyMatch(vector -> text(vector, "kind").equals(kind)
                            && flag(vector, "accepted")),
                    kind + " has no vector this build accepts");
            assertTrue(vectors().stream().anyMatch(vector -> text(vector, "kind").equals(kind)
                            && !flag(vector, "accepted")),
                    kind + " has no vector this build refuses");
        });
    }

    @Test
    @DisplayName("the client's own schema-digest vectors pass unchanged, and an altered copy fails")
    void theClientsVectorsPassUnchanged() {
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                JobEventTest.document(JobEventTest.read(CLIENT_DIGEST_VECTORS)));
        final List<DocumentValue.Mapping> vectors = assertInstanceOf(DocumentValue.Sequence.class,
                document.member("vectors").orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Mapping.class, item))
                .toList();
        assertTrue(vectors.size() >= 4, "the client's vector file lost rows");
        vectors.forEach(vector -> {
            final String canonical = text(vector, "canonical");
            assertEquals(text(vector, "sha256"),
                    Digest.of(canonical.getBytes(StandardCharsets.UTF_8)).rendered(),
                    "the client's vector for " + text(vector, "note") + " digests to something"
                            + " else here");
            assertEquals(canonical, canonical(canonical),
                    "the client's own canonical bytes are not canonical to this side");
        });
        final String altered = text(vectors.getFirst(), "canonical").replace("}", " }");
        assertFalse(text(vectors.getFirst(), "sha256")
                        .equals(Digest.of(altered.getBytes(StandardCharsets.UTF_8)).rendered()),
                "an altered copy of the client's vector still passed");
    }

    private static boolean accepts(String kind, String input) {
        final BoundedDocumentReader.Outcome read = BoundedDocumentReader.read(
                input.getBytes(StandardCharsets.UTF_8), DOCUMENT_BOUNDS);
        if (read instanceof BoundedDocumentReader.Refused) {
            return false;
        }
        return readsAs(kind, ((BoundedDocumentReader.Read) read).value());
    }

    private static boolean readsAs(String kind, DocumentValue document) {
        return switch (kind) {
            case "command-contract" -> CommandContractIdentity.of(document,
                    CommandContractIdentity.Bounds.from(CONTRACT))
                    instanceof CommandContractIdentity.Held;
            case "operation" -> OperationIdentity.of(document, CONTRACT)
                    instanceof OperationIdentity.Held;
            case "provenance" -> DocumentProvenance.of(document, build(),
                    CommandContractIdentity.Bounds.from(CONTRACT))
                    instanceof DocumentProvenance.Held;
            case "envelope" -> AgentEnvelope.read(document, build(), CONTRACT)
                    instanceof AgentEnvelope.Held;
            case "error" -> AgentError.read(document, AgentError.Bounds.from(CONTRACT))
                    instanceof AgentError.Held;
            case "job-event" -> JobEvent.read(document, SERVING, CONTRACT)
                    instanceof JobEvent.Held;
            case "job-snapshot" -> JobSnapshot.read(document, SERVING, CONTRACT)
                    instanceof JobSnapshot.Held;
            case "job-result" -> CommandResult.read(document, CONTRACT)
                    instanceof CommandResult.Held;
            case "job-failure" -> CommandFailure.read(document) instanceof CommandFailure.Held;
            case "continuation-state" -> ContinuationState.of(document)
                    instanceof ContinuationState.Held;
            case "continuation-token" -> readsAsToken(document);
            case "capabilities" -> readsAsCapabilities(document);
            case "load-content-as-json-argument" -> LoadContentCommand.of(document,
                    CONTRACT.value(ContractLimit.MAXIMUM_LOAD_DEPTH))
                    instanceof LoadContentCommand.Held;
            case "query-paths-argument" ->
                    QueryPathsCommand.of(document, CONTRACT) instanceof QueryPathsCommand.Held;
            case "list-child-pages-argument" -> ListChildPagesCommand.of(document, CONTRACT)
                    instanceof ListChildPagesCommand.Held;
            case "update-page-argument" ->
                    UpdatePageCommand.of(document, CONTRACT) instanceof UpdatePageCommand.Held;
            case "delete-page-argument" ->
                    DeletePageCommand.of(document, CONTRACT) instanceof DeletePageCommand.Held;
            case "move-page-argument" ->
                    MovePageCommand.of(document, CONTRACT) instanceof MoveRequest.Held;
            case "create-asset-folder-argument" -> CreateAssetFolderCommand.of(document, CONTRACT)
                    instanceof CreateAssetFolderCommand.Held;
            case "create-asset-argument" -> CreateAssetCommand.of(document, CONTRACT)
                    instanceof CreateAssetCommand.Held;
            case "update-asset-metadata-argument" ->
                    UpdateAssetMetadataCommand.of(document, CONTRACT)
                            instanceof UpdateAssetMetadataCommand.Held;
            case "delete-asset-argument" -> DeleteAssetCommand.of(document, CONTRACT)
                    instanceof DeleteAssetCommand.Held;
            case "move-asset-argument" -> MoveAssetCommand.of(document, CONTRACT)
                    instanceof MoveRequest.Held;
            case "add-component-argument" ->
                    AddComponentCommand.of(document, CONTRACT)
                            instanceof AddComponentCommand.Held;
            case "update-component-argument" -> UpdateComponentCommand.of(document, CONTRACT)
                    instanceof ComponentPathCommand.Held;
            case "delete-component-argument" -> DeleteComponentCommand.of(document, CONTRACT)
                    instanceof ComponentPathCommand.Held;
            case "reorder-component-argument" -> ReorderComponentCommand.of(document, CONTRACT)
                    instanceof ComponentPathCommand.Placed;
            case "create-page-argument" ->
                    CreatePageCommand.of(document, CONTRACT) instanceof CreatePageCommand.Held;
            case "list-resource-mappings-argument" ->
                    ListResourceMappingsCommand.of(document, CONTRACT)
                            instanceof ListResourceMappingsCommand.Held;
            case "resolve-resource-path-argument" ->
                    ResolveResourcePathCommand.of(document, CONTRACT)
                            instanceof ResolveResourcePathCommand.Held;
            case "map-resource-path-argument" ->
                    MapResourcePathCommand.of(document, CONTRACT)
                            instanceof MapResourcePathCommand.Held;
            case "download-content-package-argument" ->
                    DownloadContentPackageCommand.of(document, CONTRACT)
                            instanceof DownloadContentPackageCommand.Held;
            case "read-content-fragment-argument" ->
                    ReadContentFragmentCommand.of(document, CONTRACT)
                            instanceof ReadContentFragmentCommand.Held;
            case "list-asset-renditions-argument" ->
                    ListAssetRenditionsCommand.of(document, CONTRACT)
                            instanceof ListAssetRenditionsCommand.Held;
            case "find-assets-referenced-by-page-argument" ->
                    FindAssetsReferencedByPageCommand.of(document, CONTRACT)
                            instanceof FindAssetsReferencedByPageCommand.Held;
            case "find-assets-by-metadata-argument" ->
                    FindAssetsByMetadataCommand.of(document, CONTRACT)
                            instanceof FindAssetsByMetadataCommand.Held;
            case "find-pages-using-components-argument" ->
                    FindPagesUsingComponentsCommand.of(document, CONTRACT)
                            instanceof FindPagesUsingComponentsCommand.Held;
            case "find-pages-by-template-argument" ->
                    FindPagesByTemplateCommand.of(document, CONTRACT)
                            instanceof FindPagesByTemplateCommand.Held;
            case "find-pages-containing-phrase-argument" ->
                    FindPagesContainingPhraseCommand.of(document, CONTRACT)
                            instanceof FindPagesContainingPhraseCommand.Held;
            case "create-content-fragment-argument" ->
                    CreateContentFragmentCommand.of(document, CONTRACT)
                            instanceof CreateContentFragmentCommand.Held;
            case "update-content-fragment-argument" ->
                    UpdateContentFragmentCommand.of(document, CONTRACT)
                            instanceof UpdateContentFragmentCommand.Held;
            case "create-experience-fragment-argument" ->
                    CreateExperienceFragmentCommand.of(document, CONTRACT)
                            instanceof CreateExperienceFragmentCommand.Held;
            case "update-experience-fragment-argument" ->
                    UpdateExperienceFragmentCommand.of(document, CONTRACT)
                            instanceof UpdateExperienceFragmentCommand.Held;
            case "delete-content-fragment-argument", "delete-experience-fragment-argument" ->
                    FragmentDeletion.of(document, CONTRACT) instanceof FragmentDeletion.Held;
            case "replicate-content-argument" -> ReplicateContentCommand.of(document, CONTRACT)
                    instanceof ReplicateContentCommand.Held;
            case "find-configurations-argument" ->
                    FindConfigurationsCommand.of(document, CONTRACT)
                            instanceof FindConfigurationsCommand.Held;
            case "inspect-configuration-argument", "delete-configuration-argument" ->
                    ConfigurationIdentifierCommand.of(document, CONTRACT)
                            instanceof ConfigurationIdentifierCommand.Held;
            case "update-configuration-argument" ->
                    UpdateConfigurationCommand.of(document, CONTRACT)
                            instanceof UpdateConfigurationCommand.Held;
            case "list-bundles-argument" -> ListBundlesCommand.of(document, CONTRACT)
                    instanceof ListBundlesCommand.Held;
            case "list-components-argument" -> ListComponentsCommand.of(document, CONTRACT)
                    instanceof ListComponentsCommand.Held;
            case "set-bundle-state-argument" -> SetBundleStateCommand.of(document, CONTRACT)
                    instanceof SetBundleStateCommand.Held;
            case "list-workflow-models-argument" ->
                    ListWorkflowModelsCommand.of(document, CONTRACT)
                            instanceof ListWorkflowModelsCommand.Held;
            case "start-workflow-argument" -> StartWorkflowCommand.of(document, CONTRACT)
                    instanceof StartWorkflowCommand.Held;
            case "find-workflow-instances-argument" ->
                    FindWorkflowInstancesCommand.of(document, CONTRACT)
                            instanceof FindWorkflowInstancesCommand.Held;
            case "inspect-workflow-instance-argument", "terminate-workflow-instance-argument" ->
                    WorkflowInstanceCommand.of(document, CONTRACT)
                            instanceof WorkflowInstanceCommand.Held;
            case "set-workflow-suspension-argument" ->
                    WorkflowInstanceCommand.suspension(document, CONTRACT)
                            instanceof WorkflowInstanceCommand.Suspension;
            case "list-sling-job-queues-argument" -> JobCommands.queues(document, CONTRACT)
                    instanceof JobCommands.QueueWindow;
            case "find-sling-jobs-argument" -> JobCommands.search(document, CONTRACT)
                    instanceof JobCommands.Search;
            case "inspect-sling-job-argument", "cancel-sling-job-argument" ->
                    JobCommands.identifier(document, CONTRACT) instanceof JobCommands.Identifier;
            case "create-user-argument" -> PrincipalCommands.creation(document,
                    rs.slingshot.agent.command.platform.PrincipalDirectory.Kind.USER, CONTRACT)
                    instanceof PrincipalCommands.Creation;
            case "create-group-argument" -> PrincipalCommands.creation(document,
                    rs.slingshot.agent.command.platform.PrincipalDirectory.Kind.GROUP, CONTRACT)
                    instanceof PrincipalCommands.Creation;
            case "update-user-profile-argument" -> PrincipalCommands.profile(document, CONTRACT)
                    instanceof PrincipalCommands.Profile;
            case "set-user-disabled-argument" -> PrincipalCommands.account(document, CONTRACT)
                    instanceof PrincipalCommands.Account;
            case "delete-authorizable-argument" -> PrincipalCommands.removal(document, CONTRACT)
                    instanceof PrincipalCommands.Removal;
            case "add-group-member-argument", "remove-group-member-argument" ->
                    PrincipalCommands.membership(document, CONTRACT)
                            instanceof PrincipalCommands.MembershipPair;
            case "list-group-members-argument" -> PrincipalCommands.listing(document, CONTRACT)
                    instanceof PrincipalCommands.Listing;
            case "list-replication-agents-argument" -> AgentCommands.windowed(document,
                    AgentCommands.LISTING_MEMBERS, CONTRACT) instanceof AgentCommands.Windowed;
            case "inspect-replication-agent-argument" -> AgentCommands.windowed(document,
                    AgentCommands.AGENT_MEMBERS, CONTRACT) instanceof AgentCommands.Windowed;
            case "inspect-replication-queue-argument" -> AgentCommands.windowed(document,
                    AgentCommands.QUEUE_MEMBERS, CONTRACT) instanceof AgentCommands.Windowed;
            case "flush-replication-queue-argument" -> AgentCommands.flush(document, CONTRACT)
                    instanceof AgentCommands.Flush;
            case "retry-replication-queue-entry-argument" ->
                    AgentCommands.retry(document, CONTRACT) instanceof AgentCommands.Retry;
            default -> throw new IllegalStateException("no model reads a " + kind);
        };
    }

    /**
     * A token document is its integrity and its state, and this build reads it as both or neither.
     */
    private static boolean readsAsToken(DocumentValue document) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return false;
        }
        if (!mapping.members().keySet().equals(Set.copyOf(ContinuationToken.MEMBERS))) {
            return false;
        }
        return mapping.member(ContinuationToken.INTEGRITY)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> DigestValue.of(((DocumentValue.Text) value).value()))
                .filter(DigestValue.Held.class::isInstance)
                .isPresent()
                && ContinuationState.of(mapping.member(ContinuationToken.STATE).orElseThrow())
                        instanceof ContinuationState.Held;
    }

    /**
     * A capability document is one this build could have answered with: the contracts it carries
     * are identities, in wire order, and no wire name is answered twice.
     */
    private static boolean readsAsCapabilities(DocumentValue document) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return false;
        }
        if (!mapping.members().keySet().equals(Set.copyOf(CapabilityDocument.MEMBERS))) {
            return false;
        }
        final List<DocumentValue> contracts = assertInstanceOf(DocumentValue.Sequence.class,
                mapping.member(CapabilityDocument.CONTRACTS).orElseThrow()).items();
        final List<CommandContractIdentity> identities = contracts.stream()
                .map(item -> CommandContractIdentity.of(item,
                        CommandContractIdentity.Bounds.from(CONTRACT)))
                .filter(CommandContractIdentity.Held.class::isInstance)
                .map(outcome -> ((CommandContractIdentity.Held) outcome).identity())
                .toList();
        return identities.size() == contracts.size()
                && CapabilityDocument.of(new rs.slingshot.agent.discovery.AdvertisedCapabilities(
                        SERVING, canonicalContractDigest(), identities,
                        rs.slingshot.agent.discovery.AdvertisedCapabilities.ContinuationAuthority
                                .NOT_READY,
                        transportDigest()),
                DOCUMENT_BOUNDS.documentBytes()) instanceof CapabilityDocument.Held;
    }

    private static DocumentProvenance.ThisBuild build() {
        return new DocumentProvenance.ThisBuild(transportDigest(), canonicalContractDigest());
    }

    private static DigestValue transportDigest() {
        return digest(AgentContract.transportContractDigest());
    }

    private static DigestValue canonicalContractDigest() {
        return digest(new String(JobEventTest.read(
                REPOSITORY.resolve("schemas/command-canonical-json-1.sha256")),
                StandardCharsets.UTF_8).strip());
    }

    private static DigestValue digest(String rendered) {
        return assertInstanceOf(DigestValue.Held.class, DigestValue.of(rendered),
                rendered + " is not a digest").digest();
    }

    private static String canonical(String input) {
        final BoundedDocumentReader.Outcome read = BoundedDocumentReader.read(
                input.getBytes(StandardCharsets.UTF_8), DOCUMENT_BOUNDS);
        return assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(assertInstanceOf(BoundedDocumentReader.Read.class, read,
                        "a vector's input is not a document this reader accepts").value()),
                "a vector's input cannot be written canonically").rendered();
    }

    private static List<DocumentValue.Mapping> vectors() {
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                JobEventTest.document(JobEventTest.read(VECTORS)));
        return assertInstanceOf(DocumentValue.Sequence.class,
                document.member("vector").orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Mapping.class, item))
                .toList();
    }

    private static String text(DocumentValue.Mapping vector, String member) {
        return assertInstanceOf(DocumentValue.Text.class,
                vector.member(member).orElseThrow(
                        () -> new IllegalStateException("a vector declares no " + member))).value();
    }

    private static boolean flag(DocumentValue.Mapping vector, String member) {
        return assertInstanceOf(DocumentValue.Flag.class, vector.member(member).orElseThrow())
                .value() == DocumentValue.Truth.TRUE;
    }
}
