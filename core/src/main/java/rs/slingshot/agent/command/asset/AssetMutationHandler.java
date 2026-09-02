// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.DeletedResourceResult;
import rs.slingshot.agent.command.mutation.MoveRequest;
import rs.slingshot.agent.command.mutation.MutationAnswer;
import rs.slingshot.agent.command.mutation.MutationOutcome;
import rs.slingshot.agent.command.mutation.ReferencePolicy;
import rs.slingshot.agent.command.mutation.RepositoryReach;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The five commands that change a digital asset library, each one commit or none.
 *
 * <p>One handler for five because what they share is everything that matters: each is held to one
 * commit, each is refused before anything is written where it cannot proceed, and each answers with
 * the address it acted on. Which of the five it is comes from the kind the handler was built for
 * rather than from an argument, so the five keep separate registry rows, failure sets and
 * bounds.</p>
 *
 * <p>Nothing here generates a rendition. The platform's own workflow does that afterwards, and the
 * answer to a creation says only how large the original is — a caller told about renditions that do
 * not exist yet would go looking for a thumbnail and conclude the asset was broken.</p>
 */
public final class AssetMutationHandler implements CommandHandler {

    /** Which of the five this handler answers. */
    public enum Kind {
        /** Makes a folder. */
        FOLDER,
        /** Makes an asset from a payload. */
        CREATION,
        /** Changes what is known about one. */
        METADATA,
        /** Removes one. */
        REMOVAL,
        /** Moves one. */
        MOVE
    }

    private final AgentContract contract;
    private final Kind kind;

    /**
     * Holds one handler for one of the five.
     *
     * @param contract the authenticated contract
     * @param kind which of the five commands this handler answers
     */
    public AssetMutationHandler(AgentContract contract, Kind kind) {
        this.contract = contract;
        this.kind = kind;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case FOLDER -> folder(arguments, resolver);
            case CREATION -> creation(arguments, resolver);
            case METADATA -> metadata(arguments, resolver);
            case REMOVAL -> removal(arguments, resolver, context);
            case MOVE -> move(arguments, resolver, context);
        };
    }

    private Answer folder(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final CreateAssetFolderCommand.Outcome asked =
                CreateAssetFolderCommand.of(arguments, contract);
        if (asked instanceof final CreateAssetFolderCommand.Refused refused) {
            return new Failed(refused.refusal() == CreateAssetFolderCommand.Refusal.TITLE_TOO_LONG
                    ? AssetHandlers.PROPERTY_REJECTED : AssetHandlers.PARENT_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session ->
                madeFolder(((CreateAssetFolderCommand.Held) asked).command(), session));
    }

    private static MutationOutcome madeFolder(CreateAssetFolderCommand command,
                                              ResourceResolver session) {
        final Resource parent = session.getResource(command.parentPath());
        if (parent == null) {
            return new MutationOutcome.Refused(AssetHandlers.PARENT_NOT_FOUND,
                    command.parentPath() + " is not a path this caller can reach");
        }
        if (session.getResource(command.targetPath()) != null) {
            return new MutationOutcome.Refused(AssetHandlers.TARGET_ALREADY_EXISTS,
                    command.targetPath() + " is already there, and this command replaces nothing");
        }
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.FOLDER_TYPE);
        if (!CreateAssetFolderCommand.NO_TITLE.equals(command.title())) {
            properties.put(ListChildPagesHandler.TITLE_PROPERTY, command.title());
        }
        return written(session, parent, command.name(), properties,
                CreateAssetFolderResult.documentOf(command.targetPath()));
    }

    private Answer creation(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final CreateAssetCommand.Outcome asked = CreateAssetCommand.of(arguments, contract);
        if (asked instanceof final CreateAssetCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session ->
                madeAsset(((CreateAssetCommand.Held) asked).command(), session));
    }

    /**
     * Which declared category one creation refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(CreateAssetCommand.Refusal refusal) {
        return switch (refusal) {
            case PAYLOAD_TOO_LARGE -> AssetHandlers.PAYLOAD_TOO_LARGE;
            case MEDIA_TYPE_UNSUPPORTED -> AssetHandlers.MEDIA_TYPE_UNSUPPORTED;
            case PAYLOAD_REJECTED, METADATA_REJECTED, NAME_REJECTED -> AssetHandlers.PAYLOAD_REJECTED;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, NOT_AN_ABSOLUTE_PATH ->
                    AssetHandlers.PARENT_NOT_FOUND;
        };
    }

    private static MutationOutcome madeAsset(CreateAssetCommand command, ResourceResolver session) {
        final Resource parent = session.getResource(command.parentPath());
        if (parent == null) {
            return new MutationOutcome.Refused(AssetHandlers.PARENT_NOT_FOUND,
                    command.parentPath() + " is not a path this caller can reach");
        }
        if (session.getResource(command.targetPath()) != null) {
            return new MutationOutcome.Refused(AssetHandlers.TARGET_ALREADY_EXISTS,
                    command.targetPath() + " is already there, and this command replaces nothing");
        }
        try {
            final Resource asset = session.create(parent, command.name(), Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.ASSET_TYPE));
            stored(session, asset, command);
            session.commit();
            return new MutationOutcome.Changed(CreateAssetResult.documentOf(asset.getPath(),
                    command.payload().byteLength()));
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(AssetHandlers.COMMIT_FAILED,
                    "the repository refused this asset: " + refused.getMessage());
        }
    }

    /**
     * Writes an asset's content, its original, and what is known about it.
     *
     * @param session the caller's own session
     * @param asset the asset's own node
     * @param command what was asked
     * @throws PersistenceException if the repository refuses any of it
     */
    private static void stored(ResourceResolver session, Resource asset,
                               CreateAssetCommand command) throws PersistenceException {
        final Resource content = session.create(asset, ListChildPagesHandler.PAGE_CONTENT,
                Map.of(ListChildPagesHandler.TYPE_PROPERTY, "dam:AssetContent"));
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ListChildPagesHandler.TYPE_PROPERTY, "nt:unstructured");
        metadata.put("dc:format", command.payload().mediaType());
        metadata.put("dam:size", command.payload().byteLength());
        command.metadata().set().forEach((name, value) -> metadata.put(name, value.stored()));
        session.create(content, "metadata", metadata);
        final Resource renditions = session.create(content, "renditions",
                Map.of(ListChildPagesHandler.TYPE_PROPERTY, "nt:folder"));
        session.create(renditions, "original", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "nt:file",
                "jcr:mimeType", command.payload().mediaType(),
                "jcr:data_length", command.payload().byteLength()));
    }

    private Answer metadata(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final UpdateAssetMetadataCommand.Outcome asked =
                UpdateAssetMetadataCommand.of(arguments, contract);
        if (asked instanceof final UpdateAssetMetadataCommand.Refused refused) {
            return new Failed(
                    refused.refusal() == UpdateAssetMetadataCommand.Refusal.CHANGE_REJECTED
                            ? AssetHandlers.PROPERTY_REJECTED : AssetHandlers.ASSET_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session ->
                changed(((UpdateAssetMetadataCommand.Held) asked).command(), session));
    }

    private static MutationOutcome changed(UpdateAssetMetadataCommand command,
                                           ResourceResolver session) {
        final Resource asset = session.getResource(command.assetPath());
        if (asset == null) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_NOT_FOUND,
                    command.assetPath() + " is not a path this caller can reach");
        }
        if (!AssetHandlers.ASSET_TYPE.equals(String.valueOf(asset.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_INVALID, command.assetPath()
                    + " is there and is not an asset; what is there is something else");
        }
        final Resource metadata = asset.getChild(AssetHandlers.METADATA_NODE);
        if (metadata == null) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_INVALID, command.assetPath()
                    + " is an asset with no metadata node, which is a repository in a state the"
                    + " platform does not produce");
        }
        final ModifiableValueMap values = metadata.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_ACCESS_DENIED,
                    command.assetPath() + " is not an asset this caller may change");
        }
        final Optional<String> immovable = command.change().immovableIn(values);
        if (immovable.isPresent()) {
            return new MutationOutcome.Refused(AssetHandlers.PROPERTY_NOT_REMOVABLE,
                    immovable.get() + " is a property this repository will not let go of, and the"
                            + " whole change is refused rather than applied without it");
        }
        command.change().set().forEach((name, value) -> values.put(name, value.stored()));
        return sealed(session, UpdateAssetMetadataResult.documentOf(command.assetPath()));
    }

    private Answer removal(DocumentValue.Mapping arguments, ResourceResolver resolver,
                           CallerContext context) {
        final DeleteAssetCommand.Outcome asked = DeleteAssetCommand.of(arguments, contract);
        if (asked instanceof final DeleteAssetCommand.Refused refused) {
            return new Failed(AssetHandlers.ASSET_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session -> removed(((DeleteAssetCommand.Held) asked).command(),
                session, contract.value(ContractLimit.MAXIMUM_DELETED_NODES),
                context.discovery().limit()));
    }

    private static MutationOutcome removed(DeleteAssetCommand command, ResourceResolver session,
                                           long bound, long budget) {
        final Resource asset = session.getResource(command.assetPath());
        if (asset == null) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_NOT_FOUND, command.assetPath()
                    + " is not there. A caller told a delete succeeded believes something is gone"
                    + " that is not.");
        }
        if (!AssetHandlers.ASSET_TYPE.equals(String.valueOf(asset.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_INVALID, command.assetPath()
                    + " is there and is not an asset; what is there is something else");
        }
        final List<String> subtree = RepositoryReach.under(asset, bound);
        if (subtree.size() > bound) {
            return new MutationOutcome.Refused(AssetHandlers.DELETION_BUDGET_EXCEEDED,
                    "this asset holds more than the " + bound + " nodes one delete may remove");
        }
        if (command.referencePolicy() == ReferencePolicy.REFUSE_WHEN_REFERENCED
                && !RepositoryReach.pointingAt(session, command.assetPath(), budget).isEmpty()) {
            return new MutationOutcome.Refused(AssetHandlers.ASSET_IS_REFERENCED,
                    command.assetPath() + " is referenced, and this request asked to be refused"
                            + " when it is");
        }
        try {
            session.delete(asset);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(AssetHandlers.COMMIT_FAILED,
                    "the repository refused this delete: " + refused.getMessage());
        }
        return sealed(session,
                DeletedResourceResult.documentOf(command.assetPath(), subtree.size()));
    }

    private Answer move(DocumentValue.Mapping arguments, ResourceResolver resolver,
                        CallerContext context) {
        final MoveRequest.Outcome asked = MoveAssetCommand.of(arguments, contract);
        if (asked instanceof final MoveRequest.Refused refused) {
            return new Failed(
                    refused.refusal() == MoveRequest.Refusal.DESTINATION_INSIDE_SOURCE
                            ? AssetHandlers.DESTINATION_INSIDE_SOURCE
                            : AssetHandlers.SOURCE_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        return committed(resolver, session -> moved(((MoveRequest.Held) asked).command(), session,
                contract.value(ContractLimit.MAXIMUM_ADJUSTED_REFERENCES),
                context.discovery().limit()));
    }

    private static MutationOutcome moved(MoveRequest command, ResourceResolver session,
                                         long bound, long budget) {
        if (session.getResource(command.sourcePath()) == null) {
            return new MutationOutcome.Refused(AssetHandlers.SOURCE_NOT_FOUND,
                    command.sourcePath() + " is not there, so there is nothing to move");
        }
        if (session.getResource(command.destinationPath()) != null) {
            return new MutationOutcome.Refused(AssetHandlers.DESTINATION_ALREADY_EXISTS,
                    command.destinationPath() + " is already taken");
        }
        final String parent = parentOf(command.destinationPath());
        if (session.getResource(parent) == null) {
            return new MutationOutcome.Refused(AssetHandlers.DESTINATION_PARENT_NOT_FOUND,
                    parent + " is not there, so there is nowhere to move this asset to");
        }
        if (!nameOf(command.destinationPath()).equals(nameOf(command.sourcePath()))) {
            return new MutationOutcome.Refused(AssetHandlers.COMMIT_FAILED, "this move renames the"
                    + " asset as well as moving it, and this build moves something under a new"
                    + " parent without renaming it. Nothing was changed.");
        }
        return adjusted(command, session, bound, budget, parent);
    }

    private static MutationOutcome adjusted(MoveRequest command, ResourceResolver session,
                                            long bound, long budget, String parent) {
        final List<Resource> pointing =
                command.adjustReferences() == MoveRequest.ReferenceAdjustment.FOLLOWED
                        ? RepositoryReach.pointingAt(session, command.sourcePath(), budget)
                        : List.of();
        if (pointing.size() > bound) {
            return new MutationOutcome.Refused(AssetHandlers.ADJUSTMENT_BUDGET_EXCEEDED,
                    pointing.size() + " references is more than the " + bound + " one move may"
                            + " adjust, and it is refused before the move rather than after some"
                            + " of them");
        }
        try {
            session.move(command.sourcePath(), parent);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(AssetHandlers.COMMIT_FAILED,
                    "the repository refused this move: " + refused.getMessage());
        }
        final long repointed = RepositoryReach.repointed(pointing, command.sourcePath(),
                command.destinationPath());
        return sealed(session, MoveAssetResult.documentOf(command.sourcePath(),
                command.destinationPath(), repointed));
    }

    private static MutationOutcome written(ResourceResolver session, Resource parent, String name,
                                           Map<String, Object> properties,
                                           DocumentValue.Mapping result) {
        try {
            session.create(parent, name, properties);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(AssetHandlers.COMMIT_FAILED,
                    "the repository refused this: " + refused.getMessage());
        }
        return sealed(session, result);
    }

    private static MutationOutcome sealed(ResourceResolver session, DocumentValue.Mapping result) {
        try {
            session.commit();
            return new MutationOutcome.Changed(result);
        } catch (final PersistenceException refused) {
            return new MutationOutcome.Refused(AssetHandlers.COMMIT_FAILED,
                    "the repository refused this change: " + refused.getMessage());
        }
    }

    private static Answer committed(ResourceResolver resolver, SingleCommit.Mutation mutation) {
        return MutationAnswer.of(SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver,
                mutation), AssetHandlers.COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }

    private static String parentOf(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
    }

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case FOLDER -> AssetHandlers.folderCategories();
            case CREATION -> AssetHandlers.creationCategories();
            case METADATA -> AssetHandlers.metadataCategories();
            case REMOVAL -> AssetHandlers.removalCategories();
            case MOVE -> AssetHandlers.moveCategories();
        };
    }
}
