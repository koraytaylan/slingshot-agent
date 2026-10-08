// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Independent asset progress and metadata assertions, with only synthetic repository values. */
@ExtendWith(SlingContextExtension.class)
final class AssetDiscoveryProgressTest {

    private static final String ROOT = "/content/dam/synthetic-asset-discovery";
    private static final String FIRST = ROOT + "/synthetic-first";
    private static final String SECOND = ROOT + "/synthetic-second";
    private static final String FIRST_TAG = "synthetic-first-tag";
    private static final String SECOND_TAG = "synthetic-second-tag";
    private static final String FORMAT = "application/octet-stream";
    private static final String ORIGINAL = "/jcr:content/renditions/original/jcr:content";
    private static final byte[] PAYLOAD = "synthetic".getBytes(StandardCharsets.UTF_8);
    private static final long STALE_SIZE = PAYLOAD.length + 1L;
    private static final long ROOT_AND_FIRST_ASSET = 2;
    private static final long SINGLE_MATCH = 1;
    private static final AgentContract CONTRACT = contract();
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);
    private final DiscoveryRegistry discovery = new DiscoveryRegistry(CONTRACT);

    @AfterEach
    void releaseDiscovery() {
        discovery.close();
    }

    @Test
    @DisplayName("one result page is available without completing the asset subtree")
    void firstPageDoesNotRequireTheCompleteSubtree() {
        asset(FIRST, Map.of("dc:format", FORMAT));
        asset(SECOND, Map.of("dc:format", FORMAT));
        original(FIRST);
        original(SECOND);
        final var argument = arguments(Map.of("result_window", new DocumentValue.Mapping(
                new LinkedHashMap<>(Map.of("mode", new DocumentValue.Text(ResultWindow.INITIAL_MODE),
                        "offset", new DocumentValue.Whole(ResultWindow.BEGINNING),
                        "limit", new DocumentValue.Whole(SINGLE_MATCH))))));
        final var answer = new FindAssetsByMetadataHandler(CONTRACT, discovery).run(argument,
                PageSearchTestSupport.readOnly(sling.resourceResolver()),
                PageSearchTestSupport.context(CONTRACT, operation(), ROOT_AND_FIRST_ASSET));
        final var result = assertInstanceOf(CommandHandler.Produced.class, answer,
                "a first page required completing a subtree beyond this request's work budget").result();
        assertEquals(SINGLE_MATCH, matches(result).size());
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                result.member(FindAssetsByMetadataResult.COMPLETE).orElseThrow());
        assertEquals(new DocumentValue.Whole(ROOT_AND_FIRST_ASSET),
                result.member(FindAssetsByMetadataResult.EXAMINED_NODES).orElseThrow());
        assertTrue(result.member(FindAssetsByMetadataResult.NEXT_CONTINUATION_TOKEN).isPresent());
    }

    @Test
    @DisplayName("an absent metadata format falls back to the original rendition MIME type")
    void formatFallsBackToTheOriginal() {
        asset(FIRST, Map.of());
        original(FIRST);
        assertEquals(Optional.of(new DocumentValue.Text(FORMAT)),
                first(arguments(Map.of())).member("media_format"));
    }

    @Test
    @DisplayName("reported byte size is the original binary length rather than stale metadata")
    void sizeComesFromTheOriginalBinary() {
        asset(FIRST, Map.of("dc:format", FORMAT, "dam:size", STALE_SIZE));
        original(FIRST);
        assertEquals(Optional.of(new DocumentValue.Whole(PAYLOAD.length)),
                first(arguments(Map.of())).member("byte_length"));
    }

    @Test
    @DisplayName("a size filter excludes an asset whose original size is unknown")
    void missingOriginalDoesNotMatchASizeFilter() {
        asset(FIRST, Map.of("dc:format", FORMAT, "dam:size", STALE_SIZE));
        assertEquals(List.of(), matches(result(arguments(Map.of(
                "maximum_byte_length", new DocumentValue.Whole(STALE_SIZE))))));
    }

    @Test
    @DisplayName("omitted tag mode requires every requested tag as the consumer contract declares")
    void omittedTagModeRequiresAllRequestedTags() {
        asset(FIRST, Map.of("cq:tags", new String[]{FIRST_TAG}));
        asset(SECOND, Map.of("cq:tags", new String[]{FIRST_TAG, SECOND_TAG}));
        final var result = result(arguments(Map.of("tags", new DocumentValue.Sequence(List.of(
                new DocumentValue.Text(FIRST_TAG), new DocumentValue.Text(SECOND_TAG))))));
        assertEquals(List.of(new DocumentValue.Text(SECOND)), matches(result).stream()
                .map(value -> assertInstanceOf(DocumentValue.Mapping.class, value)
                        .member("repository_path").orElseThrow()).toList());
    }

    @Test
    @DisplayName("returned tags are deduplicated and ordered independently of stored array order")
    void returnedTagsHaveOneCanonicalOrder() {
        asset(FIRST, Map.of("cq:tags", new String[]{SECOND_TAG, FIRST_TAG, SECOND_TAG}));
        assertEquals(Optional.of(new DocumentValue.Sequence(List.of(new DocumentValue.Text(FIRST_TAG),
                new DocumentValue.Text(SECOND_TAG)))), first(arguments(Map.of())).member("tags"));
    }

    @Test
    void rootAssetWithoutMetadataRemainsDiscoverable() {
        sling.create().resource(ROOT, Map.of("jcr:primaryType", "dam:Asset"));
        assertEquals(List.of(new DocumentValue.Mapping(new LinkedHashMap<>(Map.of(
                "repository_path", new DocumentValue.Text(ROOT))))), matches(result(arguments(Map.of()))));
    }

    @Test
    void tagOrderingUsesUtf8BytesRatherThanUtf16Units() {
        final String supplementary = "\uD83D\uDE00";
        final String basic = "\uE000";
        asset(FIRST, Map.of("cq:tags", new String[]{supplementary, basic}));
        assertEquals(Optional.of(new DocumentValue.Sequence(List.of(new DocumentValue.Text(basic),
                new DocumentValue.Text(supplementary)))), first(arguments(Map.of())).member("tags"));
    }

    @Test
    void invalidOrMultipleMetadataFormatFallsBackToTheOriginal() {
        asset(FIRST, Map.of("dc:format", new String[]{"synthetic-one", "synthetic-two"}));
        original(FIRST);
        assertEquals(Optional.of(new DocumentValue.Text(FORMAT)),
                first(arguments(Map.of())).member("media_format"));
    }

    @Test
    void tagAtTheOriginalByteBoundIsRetainedAndOneMoreRefusesTheCursor() {
        final int bound = Math.toIntExact(CONTRACT.value(ContractLimit.MAXIMUM_ASSET_TAG_BYTES));
        final String exact = "s".repeat(bound);
        asset(FIRST, Map.of("cq:tags", new String[]{exact}));
        assertEquals(Optional.of(new DocumentValue.Sequence(List.of(new DocumentValue.Text(exact)))),
                first(arguments(Map.of())).member("tags"));
        asset(SECOND, Map.of("cq:tags", new String[]{exact + "s"}));
        assertEquals("discovery_budget_exceeded", assertInstanceOf(CommandHandler.Failed.class,
                answer(arguments(Map.of()))).category());
    }

    @Test
    void continuationReplaysOnlyWhileItsCurrentMetadataRemainsUnchanged()
            throws org.apache.sling.api.resource.PersistenceException {
        asset(FIRST, Map.of("cq:tags", new String[]{FIRST_TAG}));
        asset(SECOND, Map.of("cq:tags", new String[]{SECOND_TAG}));
        final var window = new DocumentValue.Mapping(new LinkedHashMap<>(Map.of(
                ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE),
                ResultWindow.OFFSET, new DocumentValue.Whole(ResultWindow.BEGINNING),
                ResultWindow.LIMIT, new DocumentValue.Whole(SINGLE_MATCH))));
        final var initial = result(arguments(Map.of(ResultWindow.ARGUMENT_MEMBER, window)));
        final var continuation = new DocumentValue.Mapping(new LinkedHashMap<>(Map.of(
                ResultWindow.MODE, new DocumentValue.Text(ResultWindow.CONTINUATION_MODE),
                ResultWindow.TOKEN, initial.member(FindAssetsByMetadataResult.NEXT_CONTINUATION_TOKEN)
                        .orElseThrow())));
        final var argument = arguments(Map.of(ResultWindow.ARGUMENT_MEMBER, continuation));
        final var next = result(argument);
        assertEquals(next, result(argument));
        final var returned = assertInstanceOf(DocumentValue.Mapping.class, matches(next).getFirst());
        final String path = assertInstanceOf(DocumentValue.Text.class,
                returned.member(FindAssetsByMetadataResult.REPOSITORY_PATH).orElseThrow()).value();
        final var metadata = Optional.ofNullable(sling.resourceResolver()
                .getResource(path + "/jcr:content/metadata")).orElseThrow();
        Optional.ofNullable(metadata.adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class))
                .orElseThrow().put("cq:tags", new String[]{"synthetic-changed-tag"});
        sling.resourceResolver().commit();
        assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                answer(argument)).category());
    }

    private void asset(String path, Map<String, Object> metadata) {
        sling.create().resource(path, Map.of("jcr:primaryType", "dam:Asset"));
        sling.create().resource(path + "/jcr:content/metadata", metadata);
    }

    private void original(String path) {
        final var resource = sling.create().resource(path + ORIGINAL, Map.of("jcr:mimeType", FORMAT));
        final var node = Objects.requireNonNull(resource.adaptTo(javax.jcr.Node.class));
        try {
            node.setProperty("jcr:data", node.getSession().getValueFactory().createBinary(
                    new java.io.ByteArrayInputStream(PAYLOAD)));
        } catch (final javax.jcr.RepositoryException failure) {
            throw new AssertionError(failure);
        }
    }

    private DocumentValue.Mapping result(DocumentValue.Mapping argument) {
        return assertInstanceOf(CommandHandler.Produced.class, answer(argument)).result();
    }

    private CommandHandler.Answer answer(DocumentValue.Mapping argument) {
        return new FindAssetsByMetadataHandler(CONTRACT, discovery).run(argument,
                        PageSearchTestSupport.readOnly(sling.resourceResolver()),
                        PageSearchTestSupport.context(CONTRACT, operation(),
                                CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_CANDIDATE_NODES)));
    }

    private DocumentValue.Mapping first(DocumentValue.Mapping argument) {
        return assertInstanceOf(DocumentValue.Mapping.class, matches(result(argument)).getFirst());
    }

    private static List<DocumentValue> matches(DocumentValue.Mapping result) {
        return assertInstanceOf(DocumentValue.Sequence.class, result.member("matches").orElseThrow()).items();
    }

    private static DocumentValue.Mapping arguments(Map<String, DocumentValue> narrowings) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put("root_path", new DocumentValue.Text(ROOT));
        members.putAll(narrowings);
        return new DocumentValue.Mapping(members);
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of("c".repeat(DigestValue.RENDERED_LENGTH), CONTRACT)).identifier();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load()).contract();
    }
}
