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
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.command.search.PredicateOperator;
import rs.slingshot.agent.command.search.PropertyPredicate;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Searching assets by metadata, and refusing the question nobody indexed.
 *
 * <p>Two claims matter. A predicate outside the closed set is refused when the argument is read,
 * not part way through a walk — a customer's own invented property is exactly the one no index
 * covers, and a digital asset library is the largest thing in most repositories. And what comes back
 * carries only the metadata the caller named: a search that handed back a whole metadata node would
 * be a content read wearing a search's name.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class FindAssetsByMetadataCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** A property a customer invented, which no index covers. */
    private static final String CUSTOMER_PROPERTY = "acme:licenceReference";

    /** The value of that property on every asset, which must never reach a caller. */
    private static final String CUSTOMER_VALUE = "a-licence-reference-nobody-asked-about";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a search naming nothing but a root is every asset under it")
    void arootAloneIsEveryAsset() {
        corpus();
        assertEquals(List.of("/content/dam/report", "/content/dam/photo"), pathsFrom(listed()),
                "a search naming no narrowing did not answer every asset under the root, which is"
                        + " a question about a library that the asset index answers from the node"
                        + " type alone");
    }

    @Test
    @DisplayName("the result carries what an operator decides from and no other property")
    void theresultCarriesNoOtherProperty() {
        corpus();
        final DocumentValue.Mapping found = listed();
        assertTrue(!String.valueOf(found).contains(CUSTOMER_VALUE),
                "a property the caller never asked about reached them. A search that hands back a"
                        + " whole metadata node is a content read wearing a search's name.");
        assertEquals(List.of(FindAssetsByMetadataResult.REPOSITORY_PATH,
                        FindAssetsByMetadataResult.BYTE_LENGTH,
                        FindAssetsByMetadataResult.MEDIA_FORMAT,
                        FindAssetsByMetadataResult.TAGS),
                new java.util.ArrayList<>(((DocumentValue.Mapping) matchesIn(found).getFirst())
                        .members().keySet()),
                "a match carries something other than the four things this result declares");
    }

    @Test
    @DisplayName("each narrowing narrows, and an asset matches only where every one of them does")
    void everynarrowingMustMatch() {
        corpus();
        assertEquals(List.of("/content/dam/report"),
                pathsFrom(listed(formats("application/pdf"))),
                "narrowing by format did not answer the one asset in that format");
        assertEquals(List.of("/content/dam/photo"), pathsFrom(listed(sized(0, SMALL_ENOUGH))),
                "narrowing by size did not answer the one asset small enough");
        assertEquals(List.of(), pathsFrom(listed(both("application/pdf", SMALL_ENOUGH))),
                "an asset matching one narrowing and not the other was answered as a match");
        assertEquals(List.of("/content/dam/report"),
                pathsFrom(listed(tagged(MatchMode.ALL, FINANCE, PUBLISHED))),
                "asking for assets carrying both tags answered one carrying only one of them");
        assertEquals(List.of("/content/dam/report", "/content/dam/photo"),
                pathsFrom(listed(tagged(MatchMode.ANY, FINANCE, PUBLISHED))),
                "asking for assets carrying either tag answered fewer than carry either");
    }

    private static SequencedMap<String, DocumentValue> tagged(MatchMode mode, String... tags) {
        final SequencedMap<String, DocumentValue> narrowings = new LinkedHashMap<>();
        narrowings.put(FindAssetsByMetadataCommand.TAGS, new DocumentValue.Sequence(
                java.util.Arrays.stream(tags)
                        .map(tag -> (DocumentValue) new DocumentValue.Text(tag))
                        .toList()));
        narrowings.put(FindAssetsByMetadataCommand.TAG_MATCH_MODE,
                new DocumentValue.Text(mode.spelling()));
        return narrowings;
    }

    /** A size the photograph is under and the report is over. */
    private static final long SMALL_ENOUGH = 2000;

    @Test
    @DisplayName("a predicate about a property the caller invented is answered rather than refused")
    void apredicateAboutAnyPropertyIsAnswered() {
        corpus();
        // The narrowings are filters over rows the asset index already returned, so a predicate
        // about a property nobody indexed costs one property read per candidate rather than a
        // query no index covers. That is what makes it answerable at all.
        final SequencedMap<String, DocumentValue> predicate = new LinkedHashMap<>();
        predicate.put(PropertyPredicate.OPERATOR,
                new DocumentValue.Text(PredicateOperator.EQUALS.spelling()));
        predicate.put(PropertyPredicate.PROPERTY_PATH,
                new DocumentValue.Text(FindAssetsByMetadataHandler.METADATA_NODE + "/"
                        + CUSTOMER_PROPERTY));
        final SequencedMap<String, DocumentValue> value = new LinkedHashMap<>();
        value.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        value.put(PropertyScalar.VALUE, new DocumentValue.Text(CUSTOMER_VALUE));
        predicate.put(PropertyPredicate.VALUE, new DocumentValue.Mapping(value));
        final SequencedMap<String, DocumentValue> asked = new LinkedHashMap<>();
        asked.put(PropertyPredicate.ARGUMENT_MEMBER, new DocumentValue.Sequence(
                List.of(new DocumentValue.Mapping(predicate))));
        assertEquals(List.of("/content/dam/report", "/content/dam/photo"),
                pathsFrom(listed(asked)),
                "a predicate every asset satisfies answered something other than every asset");
    }

    @Test
    @DisplayName("an impossible size range is refused rather than answered with nothing")
    void animpossibleRangeIsRefused() {
        assertEquals(FindAssetsByMetadataCommand.Refusal.SIZE_RANGE_EMPTY,
                refusalOf(both("application/pdf", 0, SMALL_ENOUGH, 1)).refusal(),
                "a range with nothing in it was accepted, and an empty answer to it reads as a"
                        + " library with nothing in it");
        assertEquals(FindAssetsByMetadataCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(rooted("content/dam")).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and matches what the handler can fail with")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new FindAssetsByMetadataHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
    }

    private DocumentValue.Mapping listed() {
        return listed(new LinkedHashMap<>());
    }

    private DocumentValue.Mapping listed(SequencedMap<String, DocumentValue> narrowings) {
        return assertInstanceOf(CommandHandler.Produced.class,
                new FindAssetsByMetadataHandler(CONTRACT)
                        .run(argument("/content/dam", narrowings), readOnly(), context()),
                "the search was refused").result();
    }

    private static SequencedMap<String, DocumentValue> formats(String format) {
        final SequencedMap<String, DocumentValue> narrowings = new LinkedHashMap<>();
        narrowings.put(FindAssetsByMetadataCommand.MEDIA_FORMATS,
                new DocumentValue.Sequence(List.of(new DocumentValue.Text(format))));
        return narrowings;
    }

    private static SequencedMap<String, DocumentValue> sized(long smallest, long largest) {
        final SequencedMap<String, DocumentValue> narrowings = new LinkedHashMap<>();
        narrowings.put(FindAssetsByMetadataCommand.MINIMUM_BYTE_LENGTH,
                new DocumentValue.Whole(smallest));
        narrowings.put(FindAssetsByMetadataCommand.MAXIMUM_BYTE_LENGTH,
                new DocumentValue.Whole(largest));
        return narrowings;
    }

    private static SequencedMap<String, DocumentValue> both(String format, long largest) {
        final SequencedMap<String, DocumentValue> narrowings = formats(format);
        narrowings.putAll(sized(0, largest));
        return narrowings;
    }

    private static DocumentValue.Mapping both(String format, long smallest, long largest,
                                              long unusedMarker) {
        final SequencedMap<String, DocumentValue> narrowings = formats(format);
        narrowings.putAll(sized(largest, smallest + unusedMarker - unusedMarker));
        return argument("/content/dam", narrowings);
    }

    private static DocumentValue.Mapping rooted(String root) {
        return argument(root, new LinkedHashMap<>());
    }

    private static List<DocumentValue> matchesIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(FindAssetsByMetadataResult.MATCHES)
                .orElseThrow()).items();
    }

    private static List<String> pathsFrom(DocumentValue.Mapping result) {
        return matchesIn(result).stream()
                .map(asset -> ((DocumentValue.Text) ((DocumentValue.Mapping) asset)
                        .member(FindAssetsByMetadataResult.REPOSITORY_PATH).orElseThrow()).value())
                .toList();
    }

    private void corpus() {
        sling.create().resource("/content/dam", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        asset("/content/dam/report", "application/pdf", 8000, FINANCE, PUBLISHED);
        asset("/content/dam/photo", "image/png", 1000, PUBLISHED);
    }

    /** A tag only the report carries. */
    private static final String FINANCE = "acme:department/finance";

    /** A tag both assets carry. */
    private static final String PUBLISHED = "acme:state/published";

    private void asset(String path, String format, long size, String... tags) {
        sling.create().resource(path, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FindAssetsByMetadataHandler.ASSET_TYPE));
        sling.create().resource(path + "/" + FindAssetsByMetadataHandler.METADATA_NODE, Map.of(
                FindAssetsByMetadataHandler.FORMAT_PROPERTY, format,
                FindAssetsByMetadataHandler.SIZE_PROPERTY, size,
                FindAssetsByMetadataHandler.TAGS_PROPERTY, tags,
                CUSTOMER_PROPERTY, CUSTOMER_VALUE));
    }

    private static FindAssetsByMetadataCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(FindAssetsByMetadataCommand.Refused.class,
                FindAssetsByMetadataCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(String root,
                                                  SequencedMap<String, DocumentValue> narrowings) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FindAssetsByMetadataCommand.ROOT_PATH, new DocumentValue.Text(root));
        members.putAll(narrowings);
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
                .row(FindAssetsByMetadataCommand.WIRE_NAME).orElseThrow();
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
