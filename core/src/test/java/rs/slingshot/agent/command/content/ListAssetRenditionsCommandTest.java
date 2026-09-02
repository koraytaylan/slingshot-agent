// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.apache.sling.api.resource.ResourceResolver;
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
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What an asset's renditions are, described and never delivered.
 *
 * <p>Two claims are checked rather than trusted. Nothing in the answer carries rendition bytes, and
 * nothing carries a repository path to them — a path is a way to fetch, and handing one back through
 * a command about sizes would let a caller reach content by asking a question about storage. And the
 * original is listed rather than left out, because an operator adding up what an asset costs needs
 * the largest thing in the total.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ListAssetRenditionsCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** Where the asset this suite describes lives. */
    private static final String ASSET = "/content/dam/site/hero.png";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("every rendition is listed with its size, and the original is one of them")
    void everyrenditionIsListedIncludingTheOriginal() {
        asset();
        final DocumentValue.Mapping listed = renditions();
        assertEquals(THREE, renditionsIn(listed).size(), "not every rendition was listed");
        assertEquals(List.of("original", "cq5dam.thumbnail.140.100.png", "cq5dam.web.1280.1280.png"),
                namesFrom(listed));
        assertEquals(List.of(true, false, false), namesFrom(listed).stream()
                        .map(ListAssetRenditionsResult.ORIGINAL_NAME::equals)
                        .toList(),
                "the asset's own original is not listed first among its renditions — an operator"
                        + " adding up what this asset costs would be short by the largest thing in"
                        + " the total");
        assertTrue(sizesFrom(listed).stream().allMatch(size -> size > 0),
                "a rendition was listed with no recorded size, and the size is the whole reason"
                        + " somebody asks this question");
    }

    /** How many renditions the asset carries. */
    private static final int THREE = 3;

    @Test
    @DisplayName("no answer carries rendition bytes, and every path it carries is under the asset")
    void noanswerCarriesContent() {
        asset();
        final String rendered = String.valueOf(renditions());
        assertTrue(pathsFrom(renditions()).stream().allMatch(path -> path.startsWith(ASSET + "/")),
                "a rendition was answered with a path outside the asset the caller named, so this"
                        + " answer discloses somewhere they had not already been told about: "
                        + rendered);
        assertTrue(!rendered.contains(RENDITION_BYTES),
                "rendition content reached the caller, and an answer that carried the renditions"
                        + " would be the very thing it is measuring");
        assertEquals(List.of(), everyMember(renditions()).stream()
                        .filter(member -> !ListAssetRenditionsResult.MEMBERS.contains(member))
                        .toList(),
                "the result carries a member this command does not declare");
    }

    /** Stand-in content on each rendition, which must never reach a caller. */
    private static final String RENDITION_BYTES = "the-bytes-of-this-rendition";

    @Test
    @DisplayName("something that is not an asset and an asset with no renditions are told apart")
    void thetwoInvalidAssetsAreToldApart() {
        asset();
        sling.create().resource("/content/dam/site/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        sling.create().resource("/content/dam/site/bare.png", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FindAssetsByMetadataHandler.ASSET_TYPE));
        assertEquals(ListAssetRenditionsHandler.ASSET_NOT_FOUND,
                failed("/content/dam/site/nothing-is-here").category());
        final CommandHandler.Failed folder = failed("/content/dam/site/folder");
        final CommandHandler.Failed bare = failed("/content/dam/site/bare.png");
        assertEquals(ListAssetRenditionsHandler.ASSET_INVALID, folder.category());
        assertEquals(ListAssetRenditionsHandler.ASSET_INVALID, bare.category());
        assertTrue(!folder.detail().equals(bare.detail()),
                "somebody who typed a folder and somebody whose repository is in a state the"
                        + " platform does not produce are told the same thing, and only one of them"
                        + " should be checking their spelling");
        assertTrue(bare.detail().contains("look at"), bare.detail());
    }

    @Test
    @DisplayName("the asset is required and never defaulted; an omitted window is the first page")
    void theassetIsRequiredAndTheWindowIsNot() {
        assertEquals(ListAssetRenditionsCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argumentWithout(ListAssetRenditionsCommand.ASSET_PATH)).refusal());
        assertInstanceOf(ListAssetRenditionsCommand.Held.class,
                ListAssetRenditionsCommand.of(argumentWithout(ResultWindow.ARGUMENT_MEMBER),
                        CONTRACT),
                "a caller who named an asset and no window was refused, and the client's own"
                        + " schema lets them ask that way");
        assertEquals(ListAssetRenditionsCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content/dam/x.png", 25)).refusal());
        assertEquals(ListAssetRenditionsCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument(ASSET, 0)).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and declares its own asset failures")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new ListAssetRenditionsHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
        assertTrue(row.failureCategories().stream().noneMatch(category ->
                        category.startsWith("root_")),
                "this command declares the shared root-anchor failures, and it is asked about one"
                        + " asset a caller named");
    }

    private DocumentValue.Mapping renditions() {
        return assertInstanceOf(CommandHandler.Produced.class,
                new ListAssetRenditionsHandler(CONTRACT)
                        .run(argument(ASSET, 100), readOnly(), context()),
                "the listing was refused").result();
    }

    private CommandHandler.Failed failed(String asset) {
        return assertInstanceOf(CommandHandler.Failed.class,
                new ListAssetRenditionsHandler(CONTRACT)
                        .run(argument(asset, 100), readOnly(), context()),
                asset + " was listed");
    }

    private static List<String> namesFrom(DocumentValue.Mapping result) {
        return renditionsIn(result).stream()
                .map(held -> ((DocumentValue.Text) ((DocumentValue.Mapping) held)
                        .member(ListAssetRenditionsResult.NAME).orElseThrow()).value())
                .toList();
    }

    @Test
    @DisplayName("a rendition knows whether it is the asset's own original, from its name")
    void theoriginalIsKnownByItsName() {
        assertTrue(new ListAssetRenditionsResult.Rendition(
                        ListAssetRenditionsResult.ORIGINAL_NAME, "image/png", 1,
                        "/content/dam/x/original").isOriginal(),
                "the asset's own original was not recognised as one");
        assertTrue(!new ListAssetRenditionsResult.Rendition("cq5dam.thumbnail.png", "image/png", 1,
                        "/content/dam/x/thumb").isOriginal(),
                "a generated rendition was reported as the asset's own original, and an operator"
                        + " deleting what they thought was a thumbnail would delete the source");
    }

    private static List<String> pathsFrom(DocumentValue.Mapping result) {
        return renditionsIn(result).stream()
                .map(held -> ((DocumentValue.Text) ((DocumentValue.Mapping) held)
                        .member(ListAssetRenditionsResult.REPOSITORY_PATH).orElseThrow()).value())
                .toList();
    }

    private static List<Long> sizesFrom(DocumentValue.Mapping result) {
        return renditionsIn(result).stream()
                .map(held -> ((DocumentValue.Whole) ((DocumentValue.Mapping) held)
                        .member(ListAssetRenditionsResult.BYTE_LENGTH).orElseThrow()).value())
                .toList();
    }

    private static List<DocumentValue> renditionsIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(ListAssetRenditionsResult.MATCHES)
                .orElseThrow()).items();
    }

    private static List<String> everyMember(DocumentValue value) {
        return switch (value) {
            case DocumentValue.Mapping mapping -> mapping.members().entrySet().stream()
                    .flatMap(member -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(member.getKey()),
                            everyMember(member.getValue()).stream()))
                    .toList();
            case DocumentValue.Sequence sequence -> sequence.items().stream()
                    .flatMap(item -> everyMember(item).stream())
                    .toList();
            default -> List.of();
        };
    }

    private void asset() {
        sling.create().resource(ASSET, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FindAssetsByMetadataHandler.ASSET_TYPE));
        rendition("original", "image/png", 2_048_000L);
        rendition("cq5dam.thumbnail.140.100.png", "image/png", 8_192L);
        rendition("cq5dam.web.1280.1280.png", "image/png", 512_000L);
    }

    private void rendition(String name, String mediaType, long size) {
        final String path = ASSET + "/" + ListAssetRenditionsHandler.RENDITIONS_NODE + "/" + name;
        sling.create().resource(path, Map.of("jcr:primaryType", "nt:file"));
        sling.create().resource(path + "/jcr:content", Map.of(
                ListAssetRenditionsHandler.MEDIA_TYPE_PROPERTY, mediaType,
                ListAssetRenditionsHandler.SIZE_PROPERTY, size,
                "jcr:data", RENDITION_BYTES));
    }

    private static ListAssetRenditionsCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(ListAssetRenditionsCommand.Refused.class,
                ListAssetRenditionsCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argumentWithout(String member) {
        final SequencedMap<String, DocumentValue> members =
                new LinkedHashMap<>(argument(ASSET, 25).members());
        members.remove(member);
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping argument(String asset, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ListAssetRenditionsCommand.ASSET_PATH, new DocumentValue.Text(asset));
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        window.put(ResultWindow.OFFSET, new DocumentValue.Whole(0));
        window.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        members.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(window));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row() {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry()
                .row(ListAssetRenditionsCommand.WIRE_NAME).orElseThrow();
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
