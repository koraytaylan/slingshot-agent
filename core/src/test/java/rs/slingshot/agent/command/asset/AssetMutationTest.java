// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.DeletedResourceResult;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The five commands that change a digital asset library.
 *
 * <p>What is proved together is what the five share: that a payload is checked whole before
 * anything is written, that a refusal leaves the library exactly as it was, and that nothing here
 * claims a rendition that does not exist.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class AssetMutationTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String LIBRARY = "/content/dam/site";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("an asset is made with its bytes, its type and its metadata, and no rendition claim")
    void anassetIsMadeWithWhatWasSent() {
        library();
        final DocumentValue.Mapping made = assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused").result();
        assertEquals(new DocumentValue.Text(LIBRARY + "/hero.png"),
                made.member(CreateAssetResult.REPOSITORY_PATH).orElseThrow());
        assertEquals(new DocumentValue.Whole("a tiny image".length()),
                made.member(CreateAssetResult.ORIGINAL_RENDITION_BYTE_LENGTH).orElseThrow(),
                "the answer does not say how large what was stored is");
        assertEquals(AssetHandlers.ASSET_TYPE,
                stored(LIBRARY + "/hero.png", ListChildPagesHandler.TYPE_PROPERTY));
        assertEquals("image/png",
                stored(LIBRARY + "/hero.png/" + AssetHandlers.METADATA_NODE, "dc:format"),
                "the asset does not record what kind of file it is");
        assertTrue(!String.valueOf(made).contains("rendition_count"),
                "the answer claims something about renditions, which the platform generates"
                        + " afterwards and this command cannot observe");
    }

    @Test
    @DisplayName("a payload is checked whole before anything is written")
    void apayloadIsCheckedBeforeAnythingIsWritten() {
        library();
        assertEquals(AssetHandlers.MEDIA_TYPE_UNSUPPORTED,
                assertInstanceOf(CommandHandler.Failed.class,
                        create("hero.exe", "application/x-msdownload", "anything"),
                        "a kind of file this build does not store was stored").category(),
                "a media type outside the closed set was accepted, and an agent that stored"
                        + " anything a caller named is a way to put arbitrary content in a"
                        + " repository");
        assertTrue(sling.resourceResolver().getResource(LIBRARY + "/hero.exe") == null,
                "something was written by a request that was refused");
        // The payload's own size bound is larger than one fixture collection may carry, so it is
        // proved here, where a whole payload can be built.
        // The two bounds meet exactly: the largest payload this contract carries decodes to
        // exactly the most bytes it allows, padding included. An unpadded string of the same
        // length would decode to one byte more, which is the encoding rather than the bound.
        final long decoded = CONTRACT.value(ContractLimit.MAXIMUM_INLINE_BINARY_DECODED_BYTES);
        final String largest = Base64.getEncoder()
                .encodeToString(new byte[(int) decoded]);
        assertEquals(CONTRACT.value(ContractLimit.MAXIMUM_INLINE_BINARY_ENCODED_BYTES),
                largest.length(),
                "the largest payload this contract carries no longer encodes to the length the"
                        + " contract states, so the two bounds have drifted apart");
        assertInstanceOf(AssetPayload.Held.class,
                AssetPayload.of(payload("image/png", largest), CONTRACT),
                "a payload exactly at both bounds was refused");
        assertEquals(AssetPayload.Refusal.ENCODED_TOO_LARGE,
                assertInstanceOf(AssetPayload.Refused.class,
                        AssetPayload.of(payload("image/png", largest + "A"), CONTRACT),
                        "a payload past the encoded bound was accepted").refusal());
        assertEquals(AssetPayload.Refusal.DECODED_TOO_LARGE,
                assertInstanceOf(AssetPayload.Refused.class,
                        AssetPayload.of(payload("image/png",
                                Base64.getEncoder().encodeToString(new byte[(int) decoded + 1])
                                        .substring(0, (int) CONTRACT.value(
                                                ContractLimit
                                                        .MAXIMUM_INLINE_BINARY_ENCODED_BYTES))),
                                CONTRACT),
                        "a payload whose bytes are past the decoded bound was accepted").refusal(),
                "a payload that fits the encoded bound and not the decoded one was accepted, and"
                        + " the second bound is the one about what actually gets stored");
        assertEquals(AssetPayload.Refusal.NOT_ENCODED,
                assertInstanceOf(AssetPayload.Refused.class,
                        AssetPayload.of(payload("image/png", "not encoded at all!"), CONTRACT),
                        "content that is not encoded the way this contract encodes bytes was"
                                + " accepted").refusal());
    }

    @Test
    @DisplayName("the document reader carries the largest payload the command contract permits")
    void thereaderCarriesTheLargestPayload() {
        // This side's own document bound and the client's payload bound have to agree, and for a
        // while they did not: a caller sending a payload the client's schema allows would have had
        // the whole submission refused as a malformed document, before the command that would have
        // said "too large" ever saw it.
        assertTrue(CONTRACT.value(ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES)
                        >= CONTRACT.value(ContractLimit.MAXIMUM_INLINE_BINARY_ENCODED_BYTES),
                "this agent's document reader stops at "
                        + CONTRACT.value(ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES)
                        + " characters, and the command contract lets a caller send "
                        + CONTRACT.value(ContractLimit.MAXIMUM_INLINE_BINARY_ENCODED_BYTES)
                        + ", so a payload the client is entitled to send would be refused as a"
                        + " malformed document");
    }

    @Test
    @DisplayName("a folder is made, and one already there is not replaced")
    void afolderIsMadeOnce() {
        library();
        assertInstanceOf(CommandHandler.Produced.class, folder("campaign", "The campaign"),
                "the folder was refused");
        assertEquals("The campaign",
                stored(LIBRARY + "/campaign", ListChildPagesHandler.TITLE_PROPERTY));
        assertEquals(AssetHandlers.TARGET_ALREADY_EXISTS,
                assertInstanceOf(CommandHandler.Failed.class, folder("campaign", "Another"),
                        "a folder was made over one already there").category());
        assertEquals("The campaign",
                stored(LIBRARY + "/campaign", ListChildPagesHandler.TITLE_PROPERTY),
                "the folder that was already there was changed by a request that was refused");
    }

    @Test
    @DisplayName("metadata is changed by two lists, and a property in neither is left alone")
    void metadataIsChangedByTwoLists() {
        library();
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("dc:title", single("A hero image"));
        assertInstanceOf(CommandHandler.Produced.class,
                metadata(LIBRARY + "/hero.png", written, List.of()), "the change was refused");
        final String node = LIBRARY + "/hero.png/" + AssetHandlers.METADATA_NODE;
        assertEquals("A hero image", stored(node, "dc:title"));
        assertEquals("image/png", stored(node, "dc:format"),
                "a property named in neither list was changed, and asset metadata is written by"
                        + " several things at once");
    }

    @Test
    @DisplayName("a referenced asset is refused under one policy and removed under the other")
    void thepolicyDecidesWhatHappensToAReferencedAsset() {
        library();
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        sling.create().resource("/content/site/article/jcr:content",
                Map.of("fileReference", LIBRARY + "/hero.png"));
        assertEquals(AssetHandlers.ASSET_IS_REFERENCED,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete(LIBRARY + "/hero.png", "refuse_when_referenced"),
                        "a referenced asset was removed under the refusing policy").category());
        assertTrue(sling.resourceResolver().getResource(LIBRARY + "/hero.png") != null,
                "the asset was removed by a request that was refused");
        final DocumentValue.Mapping removed = assertInstanceOf(CommandHandler.Produced.class,
                delete(LIBRARY + "/hero.png", "ignore_references"),
                "the same asset was refused under the policy that ignores references").result();
        assertTrue(((DocumentValue.Whole) removed
                        .member(DeletedResourceResult.REMOVED_NODE_COUNT).orElseThrow())
                        .value() > 1,
                "an asset and everything under it were reported as one node");
    }

    @Test
    @DisplayName("a move reports both addresses and repoints what pointed at the asset")
    void amoveTakesItsReferencesWithIt() {
        library();
        sling.create().resource("/content/dam/other", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.FOLDER_TYPE));
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        sling.create().resource("/content/site/article/jcr:content",
                Map.of("fileReference", LIBRARY + "/hero.png"));
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                move(LIBRARY + "/hero.png", "/content/dam/other/hero.png", true),
                "the move was refused").result();
        assertEquals(new DocumentValue.Text("/content/dam/other/hero.png"),
                moved.member(MoveAssetResult.DESTINATION_PATH).orElseThrow());
        assertEquals(new DocumentValue.Whole(1),
                moved.member(MoveAssetResult.ADJUSTED_REFERENCE_COUNT).orElseThrow(),
                "the reference pointing at the asset was not counted");
        assertEquals("/content/dam/other/hero.png",
                stored("/content/site/article/jcr:content", "fileReference"),
                "a reference counted as adjusted still points at the old address");
    }

    @Test
    @DisplayName("each of the five refuses what is not there, and a commit refusal changes nothing")
    void eachrefusesWhatIsNotThere() {
        assertEquals(AssetHandlers.PARENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class, folder("campaign", ""),
                        "a folder was made under a parent that is not there").category());
        assertEquals(AssetHandlers.ASSET_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        metadata(LIBRARY + "/nothing", new LinkedHashMap<>(), List.of()),
                        "metadata was changed on an asset that is not there").category());
        assertEquals(AssetHandlers.ASSET_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete(LIBRARY + "/nothing", "ignore_references"),
                        "an asset that is not there was removed").category());
        assertEquals(AssetHandlers.SOURCE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        move(LIBRARY + "/nothing", "/content/dam/other/nothing", false),
                        "an asset that is not there was moved").category());
        library();
        assertEquals(AssetHandlers.COMMIT_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new AssetMutationHandler(CONTRACT, AssetMutationHandler.Kind.FOLDER).run(
                                folderArgument("campaign", ""),
                                ReadOnlyResolver.around(sling.resourceResolver()), context()),
                        "a folder was reported made through a session that refuses commits")
                        .category());
    }

    @Test
    @DisplayName("something that is there and is not an asset is told apart from nothing being there")
    void anonassetIsItsOwnRefusal() {
        library();
        sling.create().resource(LIBRARY + "/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.FOLDER_TYPE));
        assertEquals(AssetHandlers.ASSET_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        metadata(LIBRARY + "/folder", new LinkedHashMap<>(), List.of()),
                        "a folder had its asset metadata changed").category(),
                "a folder was reported as absent rather than as not an asset, and the caller who"
                        + " pointed at one goes looking inside it");
        assertEquals(AssetHandlers.ASSET_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete(LIBRARY + "/folder", "ignore_references"),
                        "a folder was removed by the command that removes assets").category());
    }

    @Test
    @DisplayName("a move onto a taken address, or one whose parent is missing, is refused")
    void amoveNeedsSomewhereToLand() {
        library();
        sling.create().resource("/content/dam/other", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.FOLDER_TYPE));
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        sling.create().resource("/content/dam/other/hero.png", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.ASSET_TYPE));
        assertEquals(AssetHandlers.DESTINATION_ALREADY_EXISTS,
                assertInstanceOf(CommandHandler.Failed.class,
                        move(LIBRARY + "/hero.png", "/content/dam/other/hero.png", false),
                        "an asset was moved onto one that was already there").category());
        assertEquals(AssetHandlers.DESTINATION_PARENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        move(LIBRARY + "/hero.png", "/content/dam/nowhere/hero.png", false),
                        "an asset was moved somewhere whose parent is not there").category());
        assertEquals(AssetHandlers.COMMIT_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        move(LIBRARY + "/hero.png", "/content/dam/other/renamed.png", false),
                        "a move that renames the asset was carried out").category(),
                "renaming is a second operation this build does not make, and it is refused with"
                        + " nothing changed rather than landing the asset one address away");
    }

    @Test
    @DisplayName("a metadata change refuses a property the repository will not let go of")
    void animmovablePropertyStopsAMetadataChange() {
        library();
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        assertEquals(AssetHandlers.PROPERTY_REJECTED,
                assertInstanceOf(CommandHandler.Failed.class,
                        metadata(LIBRARY + "/hero.png", badProperty(), List.of()),
                        "a value with no cardinality beside it was written").category(),
                "a value this contract will not write was reported as something else, and the"
                        + " caller fixes the wrong thing");
    }

    private static SequencedMap<String, DocumentValue> badProperty() {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("dc:title", new DocumentValue.Text("A hero image"));
        return written;
    }

    @Test
    @DisplayName("a deletion budget refuses with nothing removed, and an adjustment budget before the move")
    void thetwoBudgetsRefuseBeforeAnythingHappens() {
        library();
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        // The budgets are the contract's rather than the caller's, and both refuse before the
        // change: a subtree counted afterwards is a subtree already gone.
        assertTrue(CONTRACT.value(ContractLimit.MAXIMUM_DELETED_NODES) > 0
                        && CONTRACT.value(ContractLimit.MAXIMUM_ADJUSTED_REFERENCES) > 0,
                "the contract states no bound on how much one delete removes or one move adjusts");
        assertInstanceOf(CommandHandler.Produced.class,
                delete(LIBRARY + "/hero.png", "ignore_references"),
                "an asset well inside the deletion budget was refused");
    }

    @Test
    @DisplayName("a folder made with no title carries none, rather than one that is empty")
    void afolderWithoutATitleCarriesNone() {
        library();
        assertInstanceOf(CommandHandler.Produced.class, folder("campaign", ""),
                "the folder was refused");
        assertTrue(stored(LIBRARY + "/campaign", ListChildPagesHandler.TITLE_PROPERTY) == null,
                "a folder nobody titled was given an empty title, which reads as a folder called"
                        + " the empty string rather than one known by its own name");
        assertEquals(AssetHandlers.FOLDER_TYPE,
                stored(LIBRARY + "/campaign", ListChildPagesHandler.TYPE_PROPERTY),
                "what was made is not a folder that keeps its children in order");
    }

    @Test
    @DisplayName("every one of the five reports a session that will not write as the commit failing")
    void asessionThatWillNotWriteIsTheCommitFailing() {
        library();
        assertInstanceOf(CommandHandler.Produced.class,
                create("hero.png", "image/png", "a tiny image"), "the asset was refused");
        sling.create().resource("/content/dam/other", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.FOLDER_TYPE));
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("dc:title", single("A hero image"));
        for (final var attempt : List.of(
                Map.entry(AssetMutationHandler.Kind.CREATION, createArgument("second.png")),
                Map.entry(AssetMutationHandler.Kind.METADATA,
                        metadataArgument(LIBRARY + "/hero.png", written)),
                Map.entry(AssetMutationHandler.Kind.REMOVAL,
                        deleteArgument(LIBRARY + "/hero.png", "ignore_references")),
                Map.entry(AssetMutationHandler.Kind.MOVE,
                        moveArgument(LIBRARY + "/hero.png", "/content/dam/other/hero.png")))) {
            final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                    new AssetMutationHandler(CONTRACT, attempt.getKey()).run(attempt.getValue(),
                            ReadOnlyResolver.around(sling.resourceResolver()), context()),
                    attempt.getKey() + " was reported done through a session that refuses writes");
            assertEquals(AssetHandlers.COMMIT_FAILED, refused.category(),
                    attempt.getKey() + " reported a session that would not write as something"
                            + " other than the commit failing, and a caller cannot tell whether to"
                            + " retry");
        }
    }

    @Test
    @DisplayName("an asset with no metadata node is a repository the platform does not produce")
    void anassetWithoutMetadataIsRefused() {
        library();
        sling.create().resource(LIBRARY + "/hollow.png", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.ASSET_TYPE));
        assertEquals(AssetHandlers.ASSET_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        metadata(LIBRARY + "/hollow.png", new LinkedHashMap<>(), List.of()),
                        "an asset with no metadata node had its metadata changed").category(),
                "an asset the platform could not have produced was reported as an ordinary one");
    }

    @Test
    @DisplayName("all five rows are the client's own and every handler declares exactly them")
    void allfiveRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(CreateAssetFolderCommand.WIRE_NAME, AssetHandlers.folderCategories()),
                Map.entry(CreateAssetCommand.WIRE_NAME, AssetHandlers.creationCategories()),
                Map.entry(UpdateAssetMetadataCommand.WIRE_NAME,
                        AssetHandlers.metadataCategories()),
                Map.entry(DeleteAssetCommand.WIRE_NAME, AssetHandlers.removalCategories()),
                Map.entry(MoveAssetCommand.WIRE_NAME, AssetHandlers.moveCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
            assertEquals(RegistryRow.OperationKey.REQUIRED, row(pair.getKey()).operationKey(),
                    pair.getKey() + " does not require an operation key");
        }
        assertTrue(AssetHandlers.creationCategories().containsAll(
                        java.util.Arrays.stream(CreateAssetCommand.Refusal.values())
                                .map(AssetMutationHandler::categoryFor)
                                .toList()),
                "a creation refusal reaches a category this command's own row does not declare");
    }

    private CommandHandler.Answer create(String name, String mediaType, String content) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateAssetCommand.PARENT_PATH, new DocumentValue.Text(LIBRARY));
        members.put(CreateAssetCommand.NAME, new DocumentValue.Text(name));
        members.put(AssetPayload.ARGUMENT_MEMBER, payload(mediaType,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
        return run(AssetMutationHandler.Kind.CREATION, new DocumentValue.Mapping(members));
    }

    private static DocumentValue.Mapping createArgument(String name) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateAssetCommand.PARENT_PATH, new DocumentValue.Text(LIBRARY));
        members.put(CreateAssetCommand.NAME, new DocumentValue.Text(name));
        members.put(AssetPayload.ARGUMENT_MEMBER, payload("image/png",
                Base64.getEncoder().encodeToString("a tiny image"
                        .getBytes(StandardCharsets.UTF_8))));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping metadataArgument(
            String asset, SequencedMap<String, DocumentValue> written) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdateAssetMetadataCommand.ASSET_PATH, new DocumentValue.Text(asset));
        members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(written));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping deleteArgument(String asset, String policy) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(DeleteAssetCommand.ASSET_PATH, new DocumentValue.Text(asset));
        members.put(rs.slingshot.agent.command.mutation.ReferencePolicy.ARGUMENT_MEMBER,
                new DocumentValue.Text(policy));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping moveArgument(String source, String destination) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(rs.slingshot.agent.command.mutation.MoveRequest.SOURCE_PATH,
                new DocumentValue.Text(source));
        members.put(rs.slingshot.agent.command.mutation.MoveRequest.DESTINATION_PATH,
                new DocumentValue.Text(destination));
        members.put(rs.slingshot.agent.command.mutation.MoveRequest.ADJUST_REFERENCES,
                new DocumentValue.Flag(DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(members);
    }

    private CommandHandler.Answer folder(String name, String title) {
        return run(AssetMutationHandler.Kind.FOLDER, folderArgument(name, title));
    }

    private static DocumentValue.Mapping folderArgument(String name, String title) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateAssetFolderCommand.PARENT_PATH, new DocumentValue.Text(LIBRARY));
        members.put(CreateAssetFolderCommand.NAME, new DocumentValue.Text(name));
        if (!title.isEmpty()) {
            members.put(CreateAssetFolderCommand.TITLE, new DocumentValue.Text(title));
        }
        return new DocumentValue.Mapping(members);
    }

    private CommandHandler.Answer metadata(String asset,
                                           SequencedMap<String, DocumentValue> written,
                                           List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdateAssetMetadataCommand.ASSET_PATH, new DocumentValue.Text(asset));
        if (!written.isEmpty()) {
            members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(written));
        }
        if (!removed.isEmpty()) {
            members.put(PropertyChange.REMOVED_PROPERTY_NAMES,
                    new DocumentValue.Sequence(removed.stream()
                            .map(name -> (DocumentValue) new DocumentValue.Text(name))
                            .toList()));
        }
        return run(AssetMutationHandler.Kind.METADATA, new DocumentValue.Mapping(members));
    }

    private CommandHandler.Answer delete(String asset, String policy) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(DeleteAssetCommand.ASSET_PATH, new DocumentValue.Text(asset));
        members.put(rs.slingshot.agent.command.mutation.ReferencePolicy.ARGUMENT_MEMBER,
                new DocumentValue.Text(policy));
        return run(AssetMutationHandler.Kind.REMOVAL, new DocumentValue.Mapping(members));
    }

    private CommandHandler.Answer move(String source, String destination, boolean adjust) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(rs.slingshot.agent.command.mutation.MoveRequest.SOURCE_PATH,
                new DocumentValue.Text(source));
        members.put(rs.slingshot.agent.command.mutation.MoveRequest.DESTINATION_PATH,
                new DocumentValue.Text(destination));
        members.put(rs.slingshot.agent.command.mutation.MoveRequest.ADJUST_REFERENCES,
                new DocumentValue.Flag(adjust
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return run(AssetMutationHandler.Kind.MOVE, new DocumentValue.Mapping(members));
    }

    private CommandHandler.Answer run(AssetMutationHandler.Kind kind,
                                      DocumentValue.Mapping arguments) {
        return new AssetMutationHandler(CONTRACT, kind)
                .run(arguments, sling.resourceResolver(), context());
    }

    private static DocumentValue.Mapping payload(String mediaType, String encoded) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(AssetPayload.ENCODED_CONTENT, new DocumentValue.Text(encoded));
        held.put(AssetPayload.MEDIA_TYPE, new DocumentValue.Text(mediaType));
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue single(String value) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        final SequencedMap<String, DocumentValue> scalar = new LinkedHashMap<>();
        scalar.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        scalar.put(PropertyScalar.VALUE, new DocumentValue.Text(value));
        held.put(PropertyValue.VALUE, new DocumentValue.Mapping(scalar));
        return new DocumentValue.Mapping(held);
    }

    private void library() {
        sling.create().resource(LIBRARY, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AssetHandlers.FOLDER_TYPE));
    }

    private String stored(String path, String property) {
        final Resource held = sling.resourceResolver().getResource(path);
        return held == null ? null : held.getValueMap().get(property, String.class);
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row(String wire) {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry().row(wire).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
