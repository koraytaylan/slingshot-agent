// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
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
import rs.slingshot.agent.command.mutation.ReferencePolicy;
import rs.slingshot.agent.command.page.CreatePageHandler;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The six commands that make, change and remove fragments.
 *
 * <p>What is proved together is what the six share: that a fragment is never written untyped, that
 * an element the model has never heard of is refused by name rather than stored as a loose
 * property, and that every refusal leaves the repository exactly as it was.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class FragmentMutationTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String LIBRARY = "/content/dam/site";

    private static final String MODEL = "/conf/site/settings/dam/cfm/models/article";

    private static final String EXPERIENCES = "/content/experience-fragments/site";

    private static final String TEMPLATE = "/conf/site/settings/wcm/templates/fragment";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a content fragment is made with its model, its title and its master variation")
    void acontentFragmentIsMadeFromItsModel() {
        library();
        model("headline", "body");
        final DocumentValue.Mapping made = assertInstanceOf(CommandHandler.Produced.class,
                createContent("article", "A headline", Map.of("headline", List.of("Hello"))),
                "the fragment was refused").result();
        assertEquals(new DocumentValue.Text(LIBRARY + "/article"),
                made.member(FragmentResult.REPOSITORY_PATH).orElseThrow());
        assertEquals(FragmentHandlers.CONTENT_FRAGMENT_TYPE,
                stored(LIBRARY + "/article", ListChildPagesHandler.TYPE_PROPERTY));
        assertEquals(MODEL, stored(LIBRARY + "/article/jcr:content/" + FragmentHandlers.DATA_NODE,
                        FragmentHandlers.MODEL_PROPERTY),
                "the fragment does not record which model declares it, so nothing can say which"
                        + " elements it has");
        assertEquals("Hello", stored(master("article"), "headline"));
        assertEquals("A headline",
                stored(LIBRARY + "/article/jcr:content", ListChildPagesHandler.TITLE_PROPERTY));
    }

    @Test
    @DisplayName("an element the model has never heard of is refused by name, not written loose")
    void anundeclaredElementIsRefused() {
        library();
        model("headline", "body");
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                createContent("article", "", Map.of("subtitle", List.of("Nope"))),
                "an element outside the model was written");
        assertEquals(FragmentHandlers.ELEMENT_UNKNOWN, refused.category());
        assertTrue(refused.detail().contains("subtitle"),
                "the refusal does not name the element, so the caller has to guess which one");
        assertTrue(!refused.detail().contains("Nope"),
                "the refusal repeats the caller's own content into a log other people read");
        assertTrue(sling.resourceResolver().getResource(LIBRARY + "/article") == null,
                "a fragment was left behind by a refusal");
    }

    @Test
    @DisplayName("a model that is not there is told apart from one that declares no elements")
    void thetwoModelFailuresAreDifferent() {
        library();
        assertEquals(FragmentHandlers.MODEL_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        createContent("article", "", Map.of()),
                        "a fragment was made from a model that is not there").category());
        sling.create().resource(MODEL, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.UNSTRUCTURED_TYPE));
        assertEquals(FragmentHandlers.MODEL_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        createContent("article", "", Map.of()),
                        "a fragment was made from something that is not a model").category(),
                "a model that resolves and declares nothing was reported as absent, which sends"
                        + " the caller to retype a path that is already right");
    }

    @Test
    @DisplayName("no variation named means master, and a named one that is not there is refused")
    void avariationIsWrittenWhereItWasNamed() {
        library();
        model("headline", "body");
        assertInstanceOf(CommandHandler.Produced.class,
                createContent("article", "", Map.of("headline", List.of("First"))),
                "the fragment was refused");
        assertInstanceOf(CommandHandler.Produced.class,
                updateContent(LIBRARY + "/article", "", "", Map.of("headline", List.of("Second"))),
                "the change was refused");
        assertEquals("Second", stored(master("article"), "headline"),
                "a change naming no variation did not reach the master one, which is the only"
                        + " variation every fragment has");
        assertEquals(FragmentHandlers.VARIATION_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        updateContent(LIBRARY + "/article", "german", "",
                                Map.of("headline", List.of("Zweite"))),
                        "a change was written to a variation that is not there").category(),
                "a translation was written onto the original rather than refused");
        assertEquals("Second", stored(master("article"), "headline"));
    }

    @Test
    @DisplayName("one value is stored as one string and several as an array, which is what the tools read")
    void cardinalityIsTheModelsRatherThanTheCallers() {
        library();
        model("headline", "tags");
        assertInstanceOf(CommandHandler.Produced.class,
                createContent("article", "", Map.of("headline", List.of("One"),
                        "tags", List.of("a", "b"))),
                "the fragment was refused");
        final Resource variation = assertInstanceOf(Resource.class,
                sling.resourceResolver().getResource(master("article")),
                "the master variation is not there");
        assertEquals("One", variation.getValueMap().get("headline", String.class));
        assertEquals(List.of("a", "b"), Arrays.asList(assertInstanceOf(String[].class,
                        variation.getValueMap().get("tags", String[].class),
                        "the element does not read back as several values")),
                "an element given several values did not read back as several");
    }

    @Test
    @DisplayName("a change to a fragment names its own model, and an element outside it is refused")
    void achangeIsHeldToTheFragmentsOwnModel() {
        library();
        model("headline", "body");
        assertInstanceOf(CommandHandler.Produced.class,
                createContent("article", "", Map.of()), "the fragment was refused");
        assertEquals(FragmentHandlers.ELEMENT_UNKNOWN,
                assertInstanceOf(CommandHandler.Failed.class,
                        updateContent(LIBRARY + "/article", "", "",
                                Map.of("subtitle", List.of("Nope"))),
                        "an element outside the model was written by a change").category(),
                "a change reads the model from the fragment rather than being told it, so an"
                        + " element outside it has to be refused there too");
    }

    @Test
    @DisplayName("something that is there and is not a content fragment is its own refusal")
    void anordinaryAssetIsNotAContentFragment() {
        library();
        sling.create().resource(LIBRARY + "/hero.png", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        assertEquals(FragmentHandlers.FRAGMENT_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        updateContent(LIBRARY + "/hero.png", "", "", Map.of()),
                        "an ordinary asset was changed as a fragment").category(),
                "an image and a fragment are both dam:Asset, and telling them apart by type alone"
                        + " is how a photograph gets edited as a fragment");
        assertEquals(FragmentHandlers.FRAGMENT_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        deleteContent(LIBRARY + "/hero.png", "ignore_references"),
                        "an ordinary asset was removed by the command that removes fragments")
                        .category());
    }

    @Test
    @DisplayName("an experience fragment answers both its own address and its first variation's")
    void anexperienceFragmentAnswersBothAddresses() {
        experiences();
        template();
        final DocumentValue.Mapping made = assertInstanceOf(CommandHandler.Produced.class,
                createExperience("promo", "A promotion", "web"),
                "the fragment was refused").result();
        assertEquals(new DocumentValue.Text(EXPERIENCES + "/promo"),
                made.member(CreateExperienceFragmentResult.REPOSITORY_PATH).orElseThrow());
        assertEquals(new DocumentValue.Text(EXPERIENCES + "/promo/web"),
                made.member(CreateExperienceFragmentResult.VARIATION_PATH).orElseThrow(),
                "the answer names only the container, and a caller addressing that in the next"
                        + " command finds nothing to change");
        assertEquals(TEMPLATE,
                stored(EXPERIENCES + "/promo/web/jcr:content",
                        CreatePageHandler.TEMPLATE_PROPERTY),
                "the variation does not record its template, so it renders as nothing");
    }

    @Test
    @DisplayName("an experience fragment is refused without a template, resolved or not")
    void anexperienceFragmentIsNeverMadeUntyped() {
        experiences();
        assertEquals(FragmentHandlers.TEMPLATE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        createExperience("promo", "", "web"),
                        "a fragment was made from a template that is not there").category());
        sling.create().resource(TEMPLATE, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.UNSTRUCTURED_TYPE));
        assertEquals(FragmentHandlers.TEMPLATE_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        createExperience("promo", "", "web"),
                        "a fragment was made from something that is not a template").category());
        assertTrue(sling.resourceResolver().getResource(EXPERIENCES + "/promo") == null,
                "a fragment was left behind by a refusal");
    }

    @Test
    @DisplayName("a variation is what a change addresses, and the container is refused as invalid")
    void avariationIsWhatAChangeAddresses() {
        experiences();
        template();
        assertInstanceOf(CommandHandler.Produced.class,
                createExperience("promo", "", "web"), "the fragment was refused");
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("campaign", single("spring"));
        assertInstanceOf(CommandHandler.Produced.class,
                updateExperience(EXPERIENCES + "/promo/web", "Spring", written, List.of()),
                "the change was refused");
        assertEquals("spring", stored(EXPERIENCES + "/promo/web/jcr:content", "campaign"));
        assertEquals("Spring", stored(EXPERIENCES + "/promo/web/jcr:content",
                ListChildPagesHandler.TITLE_PROPERTY));
        assertTrue(stored(EXPERIENCES + "/promo/jcr:content", "campaign") == null,
                "a change addressed at a variation reached the container as well");
    }

    @Test
    @DisplayName("a fragment something points at is removed or refused, as the request asked")
    void areferencePolicyIsTheCallersChoice() {
        library();
        model("headline");
        assertInstanceOf(CommandHandler.Produced.class,
                createContent("article", "", Map.of()), "the fragment was refused");
        sling.create().resource("/content/site/page/jcr:content", Map.of(
                "fragmentPath", LIBRARY + "/article"));
        assertEquals(FragmentHandlers.FRAGMENT_IS_REFERENCED,
                assertInstanceOf(CommandHandler.Failed.class,
                        deleteContent(LIBRARY + "/article", "refuse_when_referenced"),
                        "a fragment in use was removed under a policy that refuses").category());
        final DocumentValue.Mapping removed = assertInstanceOf(CommandHandler.Produced.class,
                deleteContent(LIBRARY + "/article", "ignore_references"),
                "a fragment was not removed under a policy that ignores references").result();
        assertEquals(new DocumentValue.Text(LIBRARY + "/article"),
                removed.member(DeletedResourceResult.REPOSITORY_PATH).orElseThrow());
        assertTrue(removed.member(DeletedResourceResult.REMOVED_NODE_COUNT).isPresent(),
                "the answer does not say how much went with the fragment");
        assertTrue(sling.resourceResolver().getResource(LIBRARY + "/article") == null,
                "the fragment is still there after a delete that succeeded");
    }

    @Test
    @DisplayName("each of the six refuses what is not there rather than reporting a change")
    void eachrefusesWhatIsNotThere() {
        assertEquals(FragmentHandlers.PARENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        createContent("article", "", Map.of()),
                        "a fragment was made under a parent that is not there").category());
        assertEquals(FragmentHandlers.FRAGMENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        updateContent(LIBRARY + "/nothing", "", "", Map.of()),
                        "a fragment that is not there was changed").category());
        assertEquals(FragmentHandlers.FRAGMENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        deleteContent(LIBRARY + "/nothing", "ignore_references"),
                        "a fragment that is not there was removed").category());
        assertEquals(FragmentHandlers.PARENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        createExperience("promo", "", "web"),
                        "a fragment was made under a parent that is not there").category());
        assertEquals(FragmentHandlers.VARIATION_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        updateExperience(EXPERIENCES + "/nothing/web", "",
                                new LinkedHashMap<>(), List.of()),
                        "a variation that is not there was changed").category());
        assertEquals(FragmentHandlers.FRAGMENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        deleteExperience(EXPERIENCES + "/nothing", "ignore_references"),
                        "a fragment that is not there was removed").category());
    }

    @Test
    @DisplayName("every one of the six reports a session that will not write as the commit failing")
    void asessionThatWillNotWriteIsTheCommitFailing() {
        library();
        model("headline");
        experiences();
        template();
        assertInstanceOf(CommandHandler.Produced.class,
                createContent("article", "", Map.of()), "the fragment was refused");
        assertInstanceOf(CommandHandler.Produced.class,
                createExperience("promo", "", "web"), "the fragment was refused");
        for (final var attempt : refusedWrites()) {
            final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                    new FragmentMutationHandler(CONTRACT, attempt.getKey()).run(attempt.getValue(),
                            ReadOnlyResolver.around(sling.resourceResolver()), context()),
                    attempt.getKey() + " was reported done through a session that refuses writes");
            assertEquals(FragmentHandlers.COMMIT_FAILED, refused.category(),
                    attempt.getKey() + " reported a session that would not write as something"
                            + " other than the commit failing, and a caller cannot tell whether to"
                            + " retry");
        }
    }

    private List<Map.Entry<FragmentMutationHandler.Kind, DocumentValue.Mapping>> refusedWrites() {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("campaign", single("spring"));
        return List.of(
                Map.entry(FragmentMutationHandler.Kind.CONTENT_CREATION,
                        contentArgument("second", "", Map.of())),
                Map.entry(FragmentMutationHandler.Kind.CONTENT_UPDATE,
                        contentChange(LIBRARY + "/article", "", "",
                                Map.of("headline", List.of("Changed")))),
                Map.entry(FragmentMutationHandler.Kind.CONTENT_REMOVAL,
                        deletionArgument(LIBRARY + "/article", "ignore_references")),
                Map.entry(FragmentMutationHandler.Kind.EXPERIENCE_CREATION,
                        experienceArgument("second", "", "web")),
                Map.entry(FragmentMutationHandler.Kind.EXPERIENCE_UPDATE,
                        experienceChange(EXPERIENCES + "/promo/web", "", written, List.of())),
                Map.entry(FragmentMutationHandler.Kind.EXPERIENCE_REMOVAL,
                        deletionArgument(EXPERIENCES + "/promo", "ignore_references")));
    }

    @Test
    @DisplayName("all six rows are the client's own and every handler declares exactly them")
    void allsixRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(CreateContentFragmentCommand.WIRE_NAME,
                        FragmentHandlers.contentCreationCategories()),
                Map.entry(UpdateContentFragmentCommand.WIRE_NAME,
                        FragmentHandlers.contentUpdateCategories()),
                Map.entry(FragmentDeletion.CONTENT_WIRE_NAME,
                        FragmentHandlers.removalCategories()),
                Map.entry(CreateExperienceFragmentCommand.WIRE_NAME,
                        FragmentHandlers.experienceCreationCategories()),
                Map.entry(UpdateExperienceFragmentCommand.WIRE_NAME,
                        FragmentHandlers.experienceUpdateCategories()),
                Map.entry(FragmentDeletion.EXPERIENCE_WIRE_NAME,
                        FragmentHandlers.removalCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
            assertEquals(RegistryRow.OperationKey.REQUIRED, row(pair.getKey()).operationKey(),
                    pair.getKey() + " does not require an operation key");
        }
        assertTrue(FragmentHandlers.contentCreationCategories().containsAll(
                        Arrays.stream(CreateContentFragmentCommand.Refusal.values())
                                .map(FragmentMutationHandler::categoryFor).toList()),
                "a creation refusal reaches a category this command's own row does not declare");
        assertTrue(FragmentHandlers.contentUpdateCategories().containsAll(
                        Arrays.stream(UpdateContentFragmentCommand.Refusal.values())
                                .map(FragmentMutationHandler::categoryFor).toList()),
                "a change refusal reaches a category this command's own row does not declare");
        assertTrue(FragmentHandlers.experienceUpdateCategories().containsAll(
                        Arrays.stream(UpdateExperienceFragmentCommand.Refusal.values())
                                .map(FragmentMutationHandler::categoryFor).toList()),
                "a change refusal reaches a category this command's own row does not declare");
    }

    private CommandHandler.Answer createContent(String name, String title,
                                                Map<String, List<String>> elements) {
        return run(FragmentMutationHandler.Kind.CONTENT_CREATION,
                contentArgument(name, title, elements));
    }

    private static DocumentValue.Mapping contentArgument(String name, String title,
                                                         Map<String, List<String>> elements) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateContentFragmentCommand.PARENT_PATH, new DocumentValue.Text(LIBRARY));
        members.put(CreateContentFragmentCommand.NAME, new DocumentValue.Text(name));
        members.put(CreateContentFragmentCommand.MODEL_PATH, new DocumentValue.Text(MODEL));
        if (!title.isEmpty()) {
            members.put(CreateContentFragmentCommand.TITLE, new DocumentValue.Text(title));
        }
        if (!elements.isEmpty()) {
            members.put(FragmentElements.ARGUMENT_MEMBER, elementsOf(elements));
        }
        return new DocumentValue.Mapping(members);
    }

    private CommandHandler.Answer updateContent(String fragment, String variation, String title,
                                                Map<String, List<String>> elements) {
        return run(FragmentMutationHandler.Kind.CONTENT_UPDATE,
                contentChange(fragment, variation, title, elements));
    }

    private static DocumentValue.Mapping contentChange(String fragment, String variation,
                                                       String title,
                                                       Map<String, List<String>> elements) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdateContentFragmentCommand.FRAGMENT_PATH, new DocumentValue.Text(fragment));
        if (!variation.isEmpty()) {
            members.put(UpdateContentFragmentCommand.VARIATION_NAME,
                    new DocumentValue.Text(variation));
        }
        if (!title.isEmpty()) {
            members.put(UpdateContentFragmentCommand.TITLE, new DocumentValue.Text(title));
        }
        if (!elements.isEmpty()) {
            members.put(FragmentElements.ARGUMENT_MEMBER, elementsOf(elements));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping elementsOf(Map<String, List<String>> elements) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        elements.forEach((name, values) -> held.put(name, values.size() == 1
                ? new DocumentValue.Text(values.getFirst())
                : new DocumentValue.Sequence(values.stream()
                        .map(value -> (DocumentValue) new DocumentValue.Text(value)).toList())));
        return new DocumentValue.Mapping(held);
    }

    private CommandHandler.Answer deleteContent(String fragment, String policy) {
        return run(FragmentMutationHandler.Kind.CONTENT_REMOVAL,
                deletionArgument(fragment, policy));
    }

    private CommandHandler.Answer deleteExperience(String fragment, String policy) {
        return run(FragmentMutationHandler.Kind.EXPERIENCE_REMOVAL,
                deletionArgument(fragment, policy));
    }

    private static DocumentValue.Mapping deletionArgument(String fragment, String policy) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FragmentDeletion.FRAGMENT_PATH, new DocumentValue.Text(fragment));
        members.put(ReferencePolicy.ARGUMENT_MEMBER, new DocumentValue.Text(policy));
        return new DocumentValue.Mapping(members);
    }

    private CommandHandler.Answer createExperience(String name, String title, String variation) {
        return run(FragmentMutationHandler.Kind.EXPERIENCE_CREATION,
                experienceArgument(name, title, variation));
    }

    private static DocumentValue.Mapping experienceArgument(String name, String title,
                                                            String variation) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CreateExperienceFragmentCommand.PARENT_PATH,
                new DocumentValue.Text(EXPERIENCES));
        members.put(CreateExperienceFragmentCommand.NAME, new DocumentValue.Text(name));
        members.put(CreateExperienceFragmentCommand.TEMPLATE_PATH,
                new DocumentValue.Text(TEMPLATE));
        members.put(CreateExperienceFragmentCommand.VARIATION_NAME,
                new DocumentValue.Text(variation));
        if (!title.isEmpty()) {
            members.put(CreateExperienceFragmentCommand.TITLE, new DocumentValue.Text(title));
        }
        return new DocumentValue.Mapping(members);
    }

    private CommandHandler.Answer updateExperience(String variation, String title,
                                                   SequencedMap<String, DocumentValue> written,
                                                   List<String> removed) {
        return run(FragmentMutationHandler.Kind.EXPERIENCE_UPDATE,
                experienceChange(variation, title, written, removed));
    }

    private static DocumentValue.Mapping experienceChange(
            String variation, String title, SequencedMap<String, DocumentValue> written,
            List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdateExperienceFragmentCommand.VARIATION_PATH,
                new DocumentValue.Text(variation));
        if (!title.isEmpty()) {
            members.put(UpdateExperienceFragmentCommand.TITLE, new DocumentValue.Text(title));
        }
        if (!written.isEmpty()) {
            members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(written));
        }
        if (!removed.isEmpty()) {
            members.put(PropertyChange.REMOVED_PROPERTY_NAMES,
                    new DocumentValue.Sequence(removed.stream()
                            .map(name -> (DocumentValue) new DocumentValue.Text(name)).toList()));
        }
        return new DocumentValue.Mapping(members);
    }

    private CommandHandler.Answer run(FragmentMutationHandler.Kind kind,
                                      DocumentValue.Mapping arguments) {
        return new FragmentMutationHandler(CONTRACT, kind)
                .run(arguments, sling.resourceResolver(), context());
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

    private static String master(String fragment) {
        return LIBRARY + "/" + fragment + "/jcr:content/" + FragmentHandlers.DATA_NODE + "/"
                + FragmentHandlers.MASTER_VARIATION;
    }

    private void library() {
        sling.create().resource(LIBRARY, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:OrderedFolder"));
    }

    private void experiences() {
        sling.create().resource(EXPERIENCES, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
    }

    private void template() {
        sling.create().resource(TEMPLATE, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
    }

    private void model(String... elements) {
        sling.create().resource(MODEL, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "cq:Template"));
        for (final String element : elements) {
            sling.create().resource(MODEL + "/" + FragmentHandlers.MODEL_ELEMENTS + "/" + element,
                    Map.of(ListChildPagesHandler.TYPE_PROPERTY,
                            FragmentHandlers.UNSTRUCTURED_TYPE));
        }
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
