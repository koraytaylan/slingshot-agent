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
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Reading one fragment in one named variation, typed the way content is typed everywhere else.
 *
 * <p>Three claims are checked. The variation is named rather than defaulted. A missing variation
 * and a missing fragment are two refusals, because two different people make those mistakes. And an
 * element's value is rendered by the very mapping the content loader uses, so a caller reading the
 * same value through either command is told the same thing about it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ReadContentFragmentCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** Where the fragment this suite reads lives. */
    private static final String FRAGMENT = "/content/dam/site/article";

    /** The model the fragment declares, which is what types its elements. */
    private static final String MODEL = "/conf/site/settings/dam/cfm/models/article";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("the answer names the fragment, its model and the variation it actually read")
    void theanswerSaysWhatItIsOf() {
        fragment();
        final DocumentValue.Mapping read = read(ReadContentFragmentCommand.MASTER_VARIATION);
        assertEquals(new DocumentValue.Text(MODEL),
                read.member(ReadContentFragmentResult.MODEL_PATH).orElseThrow());
        assertEquals(new DocumentValue.Text(FRAGMENT),
                read.member(ReadContentFragmentResult.REPOSITORY_PATH).orElseThrow(),
                "the answer does not say which fragment it is of, so a caller cannot check it"
                        + " against what they asked for");
        assertEquals(new DocumentValue.Text(ReadContentFragmentCommand.MASTER_VARIATION),
                read.member(ReadContentFragmentResult.VARIATION_NAME).orElseThrow());
        assertEquals(new DocumentValue.Text("An Article"), valuesFrom(read).get("title"));
        assertEquals(new DocumentValue.Text("1200"), valuesFrom(read).get("wordCount"),
                "an element was answered as something other than the text the client's schema"
                        + " declares every element to be");
    }

    @Test
    @DisplayName("a missing variation and a missing fragment are two distinct refusals")
    void thetwoAbsencesAreDistinct() {
        fragment();
        assertEquals(ReadContentFragmentHandler.FRAGMENT_NOT_FOUND,
                failed("/content/dam/site/nothing-is-here",
                        ReadContentFragmentCommand.MASTER_VARIATION).category());
        final CommandHandler.Failed variation = failed(FRAGMENT, "french");
        assertEquals(ReadContentFragmentHandler.VARIATION_NOT_FOUND, variation.category(),
                "a variation this fragment does not have was reported as the fragment being"
                        + " absent, and somebody who mistyped a variation from a list would go"
                        + " looking for the fragment instead");
        assertTrue(variation.detail().contains("french"), variation.detail());
    }

    @Test
    @DisplayName("a named variation is read, and it is not the master")
    void anamedVariationIsRead() {
        fragment();
        final DocumentValue.Mapping german = read("german");
        assertEquals(new DocumentValue.Text("german"),
                german.member(ReadContentFragmentResult.VARIATION_NAME).orElseThrow(),
                "the answer does not say which variation it is of");
        assertEquals(new DocumentValue.Text("Ein Artikel"), valuesFrom(german).get("title"),
                "the named variation was not read, or the master was read instead of it");
    }

    @Test
    @DisplayName("a fragment naming no model is refused rather than reported untyped")
    void afragmentWithoutAModelIsRefused() {
        sling.create().resource("/content/dam/site/untyped", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ReadContentFragmentHandler.FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/untyped/jcr:content", Map.of());
        sling.create().resource("/content/dam/site/untyped/" + ReadContentFragmentHandler.DATA_NODE,
                Map.of("title", "A title"));
        final CommandHandler.Failed failed = failed("/content/dam/site/untyped",
                ReadContentFragmentCommand.MASTER_VARIATION);
        assertEquals(ReadContentFragmentHandler.FRAGMENT_INVALID, failed.category());
        assertTrue(failed.detail().contains("nobody can interpret"),
                "the refusal does not say why a fragment with no model cannot be answered: "
                        + failed.detail());
    }

    @Test
    @DisplayName("an omitted variation is the master, and one sent empty is refused")
    void anomittedVariationIsTheMaster() {
        // The client's own schema makes the variation optional. Every fragment has a master and it
        // is what a caller who names nothing means, so an omitted name resolves to it rather than
        // refusing the commonest question this command is asked.
        assertEquals(ReadContentFragmentCommand.MASTER_VARIATION,
                assertInstanceOf(ReadContentFragmentCommand.Held.class,
                        ReadContentFragmentCommand.of(
                                argumentWithout(ReadContentFragmentCommand.VARIATION_NAME),
                                CONTRACT),
                        "a caller who named no variation was refused").command().variationName());
        assertEquals(ReadContentFragmentCommand.Refusal.VARIATION_EMPTY,
                refusalOf(argument(FRAGMENT, "  ")).refusal());
        assertEquals(ReadContentFragmentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content/dam/x", "master")).refusal());
        final long bound =
                CONTRACT.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_VARIATION_NAME_BYTES);
        assertEquals(ReadContentFragmentCommand.Refusal.VARIATION_TOO_LONG,
                refusalOf(argument(FRAGMENT, "v".repeat((int) bound + 1))).refusal());
    }

    @Test
    @DisplayName("this command's row carries no continuation category, because it pages nothing")
    void therowCarriesNoContinuationCategory() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(262144, row.resultBytes(),
                "this command answers under the inspection bound rather than the discovery bound"
                        + " every listing uses");
        assertTrue(row.failureCategories().stream()
                        .noneMatch(category -> category.startsWith("continuation_token_")),
                "this command declares continuation failures and it answers one fragment rather"
                        + " than a page of anything: " + row.failureCategories());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new ReadContentFragmentHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
    }

    @Test
    @DisplayName("a fragment's title is carried where it has one, and a list element stays a list")
    void atitleAndAListSurvive() {
        final DocumentValue.Mapping rendered = ReadContentFragmentResult.documentOf(
                "/content/dam/site/article", MODEL, "master", "An Article",
                List.of(new ReadContentFragmentResult.Element("tags", List.of("a", "b")),
                        new ReadContentFragmentResult.Element("title", List.of("An Article"))));
        assertEquals(new DocumentValue.Text("An Article"),
                rendered.member(ReadContentFragmentResult.TITLE).orElseThrow());
        final DocumentValue.Mapping elements = (DocumentValue.Mapping) rendered
                .member(ReadContentFragmentResult.ELEMENTS).orElseThrow();
        assertInstanceOf(DocumentValue.Sequence.class, elements.member("tags").orElseThrow(),
                "an element declared as a list was answered as one value, so the fragment reads"
                        + " back with a different shape than its model gave it");
        assertInstanceOf(DocumentValue.Text.class, elements.member("title").orElseThrow(),
                "an element holding one value was answered as a list of one");
        assertTrue(ReadContentFragmentResult.documentOf("/content/dam/site/article", MODEL,
                        "master", "", List.of()).member(ReadContentFragmentResult.TITLE).isEmpty(),
                "a fragment called nothing was answered as being called the empty string");
    }

    private DocumentValue.Mapping read(String variation) {
        return assertInstanceOf(CommandHandler.Produced.class,
                new ReadContentFragmentHandler(CONTRACT)
                        .run(argument(FRAGMENT, variation), readOnly(), context()),
                "the fragment was refused").result();
    }

    private CommandHandler.Failed failed(String fragment, String variation) {
        return assertInstanceOf(CommandHandler.Failed.class,
                new ReadContentFragmentHandler(CONTRACT)
                        .run(argument(fragment, variation), readOnly(), context()),
                fragment + " was read");
    }

    private static Map<String, DocumentValue> valuesFrom(DocumentValue.Mapping result) {
        return new LinkedHashMap<>(((DocumentValue.Mapping) result
                .member(ReadContentFragmentResult.ELEMENTS).orElseThrow()).members());
    }

    private void fragment() {
        sling.create().resource(FRAGMENT, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ReadContentFragmentHandler.FRAGMENT_TYPE));
        sling.create().resource(FRAGMENT + "/jcr:content", Map.of(
                ReadContentFragmentHandler.MODEL_PROPERTY, MODEL));
        sling.create().resource(FRAGMENT + "/" + ReadContentFragmentHandler.DATA_NODE, Map.of(
                "title", "An Article", "wordCount", 1200L, "published", true));
        sling.create().resource(FRAGMENT + "/" + ReadContentFragmentHandler.DATA_NODE + "/german",
                Map.of("title", "Ein Artikel", "wordCount", 1100L, "published", false));
    }

    private static ReadContentFragmentCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(ReadContentFragmentCommand.Refused.class,
                ReadContentFragmentCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argumentWithout(String member) {
        final SequencedMap<String, DocumentValue> members =
                new LinkedHashMap<>(argument(FRAGMENT, "master").members());
        members.remove(member);
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping argument(String fragment, String variation) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ReadContentFragmentCommand.FRAGMENT_PATH, new DocumentValue.Text(fragment));
        members.put(ReadContentFragmentCommand.VARIATION_NAME, new DocumentValue.Text(variation));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_OPERATIONAL_INSPECTION_RESULT_BYTES)),
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
                .row(ReadContentFragmentCommand.WIRE_NAME).orElseThrow();
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
