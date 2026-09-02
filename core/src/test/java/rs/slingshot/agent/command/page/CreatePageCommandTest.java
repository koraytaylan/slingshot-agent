// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
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
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** The first command that writes, and the one that decides what a made thing looks like. */
@ExtendWith(SlingContextExtension.class)
final class CreatePageCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** The template every page in this suite is made from. */
    private static final String TEMPLATE = "/conf/site/settings/wcm/templates/article";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a page is made where it was asked for, and the answer says where it went")
    void apageIsMadeWhereItWasAsked() {
        corpus();
        final DocumentValue.Mapping made = assertInstanceOf(CommandHandler.Produced.class,
                run(argument("/content/site", "article", "An Article", TEMPLATE)),
                "the page was refused").result();
        assertEquals(new DocumentValue.Text("/content/site/article"),
                made.member(CreatePageResult.TARGET_PATH).orElseThrow(),
                "the answer does not say where the page went, so a caller cannot tell it from a"
                        + " page the repository put somewhere else");
        assertEquals(CreatePageHandler.PAGE_TYPE,
                stored("/content/site/article", ListChildPagesHandler.TYPE_PROPERTY),
                "what was made is not a page");
        assertEquals(TEMPLATE, stored("/content/site/article/jcr:content",
                        CreatePageHandler.TEMPLATE_PROPERTY),
                "the page does not record the template it was made from, so every tool that opens"
                        + " it has to guess what it is");
        assertEquals("An Article", stored("/content/site/article/jcr:content",
                ListChildPagesHandler.TITLE_PROPERTY));
    }

    @Test
    @DisplayName("a page already at the address is refused, and the one that is there is untouched")
    void anexistingPageIsNotReplaced() {
        corpus();
        assertInstanceOf(CommandHandler.Produced.class,
                run(argument("/content/site", "article", "An Article", TEMPLATE)));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                run(argument("/content/site", "article", "Another Article", TEMPLATE)),
                "a page was made over one that was already there");
        assertEquals(CreatePageHandler.TARGET_ALREADY_EXISTS, refused.category());
        assertEquals("An Article", stored("/content/site/article/jcr:content",
                        ListChildPagesHandler.TITLE_PROPERTY),
                "the page that was already there was changed by the request that was refused");
    }

    @Test
    @DisplayName("a template that is not there and one that is not a template are told apart")
    void thetwoTemplateFailuresAreDistinct() {
        corpus();
        assertEquals(CreatePageHandler.TEMPLATE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        run(argument("/content/site", "article", "An Article", "/conf/nothing")),
                        "a page was made from a template that is not there").category());
        sling.create().resource("/conf/site/settings/wcm/templates/not-a-template", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "nt:unstructured"));
        final CommandHandler.Failed invalid = assertInstanceOf(CommandHandler.Failed.class,
                run(argument("/content/site", "article", "An Article",
                        "/conf/site/settings/wcm/templates/not-a-template")),
                "a page was made from something that is not a template");
        assertEquals(CreatePageHandler.TEMPLATE_INVALID, invalid.category(),
                "something that is there and is not a template was reported as absent, and the"
                        + " caller who pointed at the wrong node goes looking for a typo instead");
        assertTrue(sling.resourceResolver().getResource("/content/site/article") == null,
                "a page was left behind by a request that was refused");
    }

    @Test
    @DisplayName("this command takes no removal list, since there is nothing yet to remove from")
    void aremovalOnCreationIsRefused() {
        assertEquals(CreatePageCommand.Refusal.MEMBER_UNKNOWN,
                refusalOf(withUnknownMember()).refusal(),
                "a member nobody declared was accepted rather than refused");
        assertTrue(!CreatePageCommand.MEMBERS.contains(PropertyChange.REMOVED_PROPERTY_NAMES),
                "this command takes a removal list, and there is nothing to remove from a page"
                        + " that does not exist yet");
    }

    @Test
    @DisplayName("an initial property is written, and a list stays a list")
    void aninitialPropertyIsWritten() {
        corpus();
        final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
        properties.put("cq:tags", multiple("news", "featured"));
        properties.put("subtitle", single("A subtitle"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>(
                argument("/content/site", "article", "An Article", TEMPLATE).members());
        members.put(CreatePageCommand.INITIAL_PROPERTIES, new DocumentValue.Mapping(properties));
        assertInstanceOf(CommandHandler.Produced.class,
                run(new DocumentValue.Mapping(members)), "the page was refused");
        assertEquals("A subtitle", stored("/content/site/article/jcr:content", "subtitle"));
        assertEquals(List.of("news", "featured"),
                List.of(values("/content/site/article/jcr:content", "cq:tags")),
                "a property declared as a list was written as one value, so the page reads back"
                        + " with a different shape than the caller asked for");
    }

    @Test
    @DisplayName("every address, name and title is bounded, and each refusal says which")
    void everyvalueIsBounded() {
        assertEquals(CreatePageCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument("/content/site", "article", "An Article", null)).refusal());
        assertEquals(CreatePageCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content/site", "article", "An Article", TEMPLATE)).refusal());
        assertEquals(CreatePageCommand.Refusal.NAME_REJECTED,
                refusalOf(argument("/content/site", "a/b", "An Article", TEMPLATE)).refusal(),
                "a name carrying a path was accepted, so a page could be made anywhere under the"
                        + " parent rather than under it");
        final long name = CONTRACT.value(ContractLimit.MAXIMUM_PAGE_NAME_BYTES);
        assertInstanceOf(CreatePageCommand.Held.class,
                CreatePageCommand.of(argument("/content/site", "n".repeat((int) name),
                        "An Article", TEMPLATE), CONTRACT),
                "a name exactly at the bound was refused");
        assertEquals(CreatePageCommand.Refusal.NAME_REJECTED,
                refusalOf(argument("/content/site", "n".repeat((int) name + 1), "An Article",
                        TEMPLATE)).refusal());
        final long title = CONTRACT.value(ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES);
        assertEquals(CreatePageCommand.Refusal.TITLE_TOO_LONG,
                refusalOf(argument("/content/site", "article", "t".repeat((int) title + 1),
                        TEMPLATE)).refusal(),
                "a title past the bound the client's own schema states was accepted");
    }

    @Test
    @DisplayName("a commit the repository refuses leaves nothing behind and says so")
    void arefusedCommitLeavesNothing() {
        corpus();
        // The read-only wrapper refuses a commit the way a repository that will not take one does,
        // which is the only path in this handler that a mock repository cannot otherwise reach.
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new CreatePageHandler(CONTRACT).run(
                        argument("/content/site", "article", "An Article", TEMPLATE),
                        ReadOnlyResolver.around(sling.resourceResolver()), context()),
                "a page was reported made through a session that refuses commits");
        assertEquals(CreatePageHandler.COMMIT_FAILED, failed.category(),
                "a commit the repository refused was reported as something other than the commit"
                        + " failing, and a caller cannot tell whether to retry");
    }

    @Test
    @DisplayName("an initial property this contract will not write is its own refusal")
    void abadInitialPropertyIsItsOwnRefusal() {
        final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
        properties.put("subtitle", new DocumentValue.Text("A subtitle"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>(
                argument("/content/site", "article", "An Article", TEMPLATE).members());
        members.put(CreatePageCommand.INITIAL_PROPERTIES, new DocumentValue.Mapping(properties));
        final CreatePageCommand.Refused refused =
                refusalOf(new DocumentValue.Mapping(members));
        assertEquals(CreatePageCommand.Refusal.PROPERTIES_REJECTED, refused.refusal(),
                "a value with no cardinality beside it was accepted, so its shape would have been"
                        + " inferred");
        assertEquals(CreatePageHandler.PROPERTY_REJECTED,
                CreatePageHandler.categoryFor(refused.refusal()),
                "a value this contract will not write was reported as something other than the"
                        + " value being rejected, and the caller fixes the wrong thing");
        assertTrue(CreatePageHandler.declaredCategories().containsAll(
                        java.util.Arrays.stream(CreatePageCommand.Refusal.values())
                                .map(CreatePageHandler::categoryFor)
                                .toList()),
                "an argument refusal reaches a category this command's own row does not declare");
    }

    @Test
    @DisplayName("this command's row is the client's own and requires an operation key")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REQUIRED, row.operationKey(),
                "making the same page twice is not making it once, so the caller supplies a key");
        assertEquals(16384, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                CreatePageHandler.declaredCategories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
        assertTrue(row.failureCategories().contains("mutation_outcome_unknown"),
                "a command that changes a repository does not declare the answer nobody knows,"
                        + " which is the one a caller must not read as a failure");
    }

    /**
     * What the repository holds under one property, having first said the node is there at all.
     *
     * <p>Asserted rather than dereferenced, so a node this suite expected and did not find fails
     * where it was looked for rather than as a null somewhere further down.</p>
     *
     * @param path the node
     * @param property the property
     * @return its value
     */
    private String stored(String path, String property) {
        return valuesAt(path).get(property, String.class);
    }

    private String[] values(String path, String property) {
        return valuesAt(path).get(property, String[].class);
    }

    private org.apache.sling.api.resource.ValueMap valuesAt(String path) {
        final var held = sling.resourceResolver().getResource(path);
        assertTrue(held != null, path + " is not there");
        return held.getValueMap();
    }

    private CommandHandler.Answer run(DocumentValue.Mapping arguments) {
        return new CreatePageHandler(CONTRACT).run(arguments, sling.resourceResolver(), context());
    }

    private void corpus() {
        sling.create().resource("/content/site", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.PAGE_TYPE));
        sling.create().resource(TEMPLATE, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
    }

    private static CreatePageCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(CreatePageCommand.Refused.class,
                CreatePageCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping withUnknownMember() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>(
                argument("/content/site", "article", "An Article", TEMPLATE).members());
        members.put(PropertyChange.REMOVED_PROPERTY_NAMES, new DocumentValue.Sequence(List.of()));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping argument(String parent, String name, String title,
                                                  String template) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreatePageCommand.PARENT_PATH, new DocumentValue.Text(parent));
        members.put(CreatePageCommand.PAGE_NAME, new DocumentValue.Text(name));
        members.put(CreatePageCommand.TITLE, new DocumentValue.Text(title));
        if (template != null) {
            members.put(CreatePageCommand.TEMPLATE_PATH, new DocumentValue.Text(template));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue single(String value) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        held.put(PropertyValue.VALUE, scalar(value));
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue multiple(String... values) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.MULTIPLE));
        held.put(PropertyValue.VALUES, new DocumentValue.Sequence(
                java.util.Arrays.stream(values)
                        .map(CreatePageCommandTest::scalar)
                        .toList()));
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue scalar(String value) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        written.put(PropertyScalar.VALUE, new DocumentValue.Text(value));
        return new DocumentValue.Mapping(written);
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

    private static RegistryRow row() {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry()
                .row(CreatePageCommand.WIRE_NAME).orElseThrow();
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
