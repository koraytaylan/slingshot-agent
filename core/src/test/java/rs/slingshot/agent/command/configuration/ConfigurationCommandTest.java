// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.command.platform.ConfigurationValue;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.ValueDisclosure;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The four commands about the platform's own configuration.
 *
 * <p>What is proved here is the shape of the boundary. Reading works on a deployment that permits
 * no change at all, because an environment an operator cannot alter is exactly the one where
 * knowing what it says matters most. Writing is refused there before the argument is even read, so
 * the caller is told about where they are running rather than about a typo.</p>
 *
 * <p>And no listing carries a value. A search across a whole instance is the call whose output ends
 * up pasted into a ticket, so what it carries has to be safe to paste.</p>
 */
final class ConfigurationCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String IMMUTABLE = "aem-cloud-service";

    private static final String SERVICE = "rs.slingshot.Service";

    private static final String SECRET = "service.password";

    @Test
    @DisplayName("a deployment that keeps no configuration change refuses both writes, and neither read")
    void animmutableDeploymentRefusesTheWritesAndNotTheReads() {
        final Catalogue catalogue = new Catalogue();
        final PlatformControl immutable = PlatformControl.of(IMMUTABLE, Set.of());
        for (final ConfigurationHandler.Kind kind : List.of(ConfigurationHandler.Kind.UPDATE,
                ConfigurationHandler.Kind.REMOVAL)) {
            final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                    new ConfigurationHandler(CONTRACT, kind, () -> catalogue, immutable)
                            .run(identifier(SERVICE), null, context()),
                    kind + " was carried out on a deployment that does not keep it");
            assertEquals(PlatformControl.NOT_PERMITTED, refused.category());
            assertTrue(refused.detail().contains(IMMUTABLE),
                    "the refusal does not say which deployment refused: " + refused.detail());
        }
        assertEquals(List.of(), catalogue.calls(),
                "the platform was asked to do something on a deployment that does not keep it,"
                        + " and a change that is accepted and then discarded is worse than none");
        assertInstanceOf(CommandHandler.Produced.class,
                new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.INSPECTION, () -> catalogue,
                        immutable).run(identifier(SERVICE), null, context()),
                "reading a configuration was refused on a deployment that cannot change one,"
                        + " which is the deployment where reading matters most");
    }

    @Test
    @DisplayName("a write is refused for where it is running before it is refused for how it is written")
    void thedeploymentIsCheckedBeforeTheArgument() {
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.UPDATE,
                        Catalogue::new, PlatformControl.of(IMMUTABLE, Set.of()))
                        .run(new DocumentValue.Mapping(new LinkedHashMap<>()), null, context()),
                "an argument with no identifier was read before the deployment was asked");
        assertEquals(PlatformControl.NOT_PERMITTED, refused.category(),
                "a caller on an environment that keeps no configuration change was told their"
                        + " argument was wrong, which sends them to fix the wrong thing");
    }

    @Test
    @DisplayName("a search answers how many properties each configuration has and never what they are")
    void asearchCarriesNoValue() {
        final DocumentValue.Mapping found = assertInstanceOf(CommandHandler.Produced.class,
                run(ConfigurationHandler.Kind.SEARCH, new DocumentValue.Mapping(
                        new LinkedHashMap<>())), "the search was refused").result();
        final String rendered = String.valueOf(found);
        assertTrue(!rendered.contains("hunter2") && !rendered.contains("8080"),
                "a search across the whole instance carried a configuration value, and a search is"
                        + " the one call whose output ends up pasted into a ticket: " + rendered);
        final DocumentValue.Sequence matches = assertInstanceOf(DocumentValue.Sequence.class,
                found.member(FindConfigurationsResult.MATCHES).orElseThrow());
        final DocumentValue.Mapping first = assertInstanceOf(DocumentValue.Mapping.class,
                matches.items().getFirst());
        assertEquals(new DocumentValue.Whole(2),
                first.member(FindConfigurationsResult.PROPERTY_KEY_COUNT).orElseThrow(),
                "the search does not say how large each configuration is, which is what tells an"
                        + " operator which one somebody has customised");
        assertTrue(first.member(FindConfigurationsResult.BOUND_TO_A_BUNDLE_LOCATION).isPresent(),
                "a search does not say whether a configuration is delivered to one bundle only,"
                        + " and a service that looks misconfigured may be receiving another one");
    }

    @Test
    @DisplayName("a prefix narrows a search, and only a prefix does")
    void onlyAPrefixNarrowsASearch() {
        final SequencedMap<String, DocumentValue> prefixed = new LinkedHashMap<>();
        prefixed.put(FindConfigurationsCommand.PERSISTENT_IDENTIFIER_PREFIX,
                new DocumentValue.Text("rs.slingshot"));
        assertInstanceOf(CommandHandler.Produced.class,
                run(ConfigurationHandler.Kind.SEARCH, new DocumentValue.Mapping(prefixed)),
                "a prefixed search was refused");
        final SequencedMap<String, DocumentValue> filtered = new LinkedHashMap<>();
        filtered.put("property_value_contains", new DocumentValue.Text("hunter2"));
        assertEquals(FindConfigurationsCommand.Refusal.MEMBER_UNKNOWN,
                assertInstanceOf(FindConfigurationsCommand.Refused.class,
                        FindConfigurationsCommand.of(new DocumentValue.Mapping(filtered), CONTRACT),
                        "a filter on values was accepted, which would mean reading every value on"
                                + " the instance to decide what matches").refusal());
        assertTrue(new FindConfigurationsCommand("rs.slingshot", ResultWindow.omitted(CONTRACT))
                        .matches(SERVICE),
                "a configuration under the prefix did not match it");
        assertTrue(!new FindConfigurationsCommand("com.adobe", ResultWindow.omitted(CONTRACT))
                        .matches(SERVICE),
                "a configuration outside the prefix matched it");
    }

    @Test
    @DisplayName("an inspection reports a described value and withholds a password and an undescribed one")
    void aninspectionWithholdsWhatItMayNotSay() {
        final DocumentValue.Mapping read = assertInstanceOf(CommandHandler.Produced.class,
                run(ConfigurationHandler.Kind.INSPECTION, identifier(SERVICE)),
                "the inspection was refused").result();
        final DocumentValue.Mapping properties = assertInstanceOf(DocumentValue.Mapping.class,
                read.member(InspectConfigurationResult.PROPERTIES).orElseThrow());
        assertTrue(String.valueOf(properties.member("service.port").orElseThrow()).contains("8080"),
                "a property the platform describes and does not call a secret was withheld");
        assertTrue(!String.valueOf(read).contains("hunter2"),
                "the answer carries a value the platform calls a password: " + read);
        final DocumentValue.Mapping secret = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(SECRET).orElseThrow());
        assertEquals(new DocumentValue.Text(ValueDisclosure.PASSWORD_EVIDENCE),
                secret.member(ValueDisclosure.METATYPE_EVIDENCE).orElseThrow(),
                "the answer does not say why the value is missing, so a caller cannot tell a"
                        + " withheld property from a broken one");
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                read.member(InspectConfigurationResult.PRESENT).orElseThrow());
    }

    @Test
    @DisplayName("a configuration that is not there is an answer, not a failure")
    void absenceIsAnAnswer() {
        final DocumentValue.Mapping read = assertInstanceOf(CommandHandler.Produced.class,
                run(ConfigurationHandler.Kind.INSPECTION, identifier("rs.slingshot.Absent")),
                "a configuration that is not there was reported as a failure, and a service"
                        + " running on its defaults is one of the most useful things to learn")
                .result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                read.member(InspectConfigurationResult.PRESENT).orElseThrow());
        assertEquals(new DocumentValue.Mapping(new LinkedHashMap<>()),
                read.member(InspectConfigurationResult.PROPERTIES).orElseThrow());
    }

    @Test
    @DisplayName("an assignment carries its own type and cardinality, and one that does not is refused")
    void anassignmentCarriesItsType() {
        final SequencedMap<String, DocumentValue> bare = new LinkedHashMap<>();
        bare.put("service.port", new DocumentValue.Text("8080"));
        assertEquals(UpdateConfigurationCommand.Refusal.VALUE_REJECTED,
                refusedUpdate(bare, List.of()).refusal(),
                "a value with no type beside it was accepted, and 8080 written back as a string is"
                        + " a configuration that no longer starts a listener");
        final SequencedMap<String, DocumentValue> typed = new LinkedHashMap<>();
        typed.put("service.port", value("integer", "scalar", List.of("9090")));
        final UpdateConfigurationCommand held = assertInstanceOf(
                UpdateConfigurationCommand.Held.class,
                UpdateConfigurationCommand.of(update(typed, List.of()), CONTRACT),
                "a typed assignment was refused").command();
        assertEquals(new ConfigurationValue("integer",
                        ConfigurationValue.Cardinality.SCALAR, List.of("9090")),
                held.assignments().get("service.port"));
        assertTrue(!held.isEmpty(), "a change naming an assignment was read as changing nothing");
    }

    @Test
    @DisplayName("a property both set and removed is refused rather than resolved in an order nobody chose")
    void aproperyBothSetAndRemovedIsRefused() {
        final SequencedMap<String, DocumentValue> assignments = new LinkedHashMap<>();
        assignments.put("service.port", value("integer", "scalar", List.of("9090")));
        assertEquals(UpdateConfigurationCommand.Refusal.SET_AND_REMOVED,
                refusedUpdate(assignments, List.of("service.port")).refusal(),
                "set-then-remove and remove-then-set leave different configurations, and one of"
                        + " the two orders was chosen silently");
        assertEquals(ConfigurationHandlers.VALUE_MALFORMED,
                ConfigurationHandler.categoryFor(
                        UpdateConfigurationCommand.Refusal.SET_AND_REMOVED));
    }

    @Test
    @DisplayName("a change touching nothing is read as touching nothing, and reaches the platform anyway")
    void achangeNamingNothingIsStillAChange() {
        final UpdateConfigurationCommand held = assertInstanceOf(
                UpdateConfigurationCommand.Held.class,
                UpdateConfigurationCommand.of(update(new LinkedHashMap<>(), List.of()), CONTRACT),
                "a change naming nothing was refused").command();
        assertTrue(held.isEmpty(),
                "a change naming neither an assignment nor a removal was read as naming one");
        assertEquals(List.of(), held.removedPropertyKeys());
    }

    @Test
    @DisplayName("a removal says whether it took one instance of a factory or the configuration itself")
    void aremovalSaysWhichKindItWas() {
        final DocumentValue.Mapping gone = assertInstanceOf(CommandHandler.Produced.class,
                run(ConfigurationHandler.Kind.REMOVAL, identifier(SERVICE)),
                "the removal was refused").result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                gone.member(DeleteConfigurationResult.WAS_A_FACTORY_INSTANCE).orElseThrow(),
                "the answer does not say whether one instance went or the whole configuration did,"
                        + " and an operator who thought they were doing the first and did the"
                        + " second has changed the behaviour of everything on the instance");
        assertEquals(new DocumentValue.Text(SERVICE),
                gone.member(DeleteConfigurationResult.PERSISTENT_IDENTIFIER).orElseThrow());
    }

    @Test
    @DisplayName("a change the platform refuses is reported as the platform refusing it")
    void achangeThePlatformRefusedIsReportedAsThat() {
        final Catalogue refusing = new Catalogue(ConfigurationHandlers.CONTROL_REJECTED,
                "the service would not accept it");
        assertEquals(ConfigurationHandlers.CONTROL_REJECTED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.UPDATE,
                                () -> refusing, permissive()).run(update(new LinkedHashMap<>(),
                                        List.of()), null, context()),
                        "a change the platform refused was reported as having happened").category());
    }

    @Test
    @DisplayName("a change that reaches the platform answers how many keys it touched")
    void achangeAnswersHowManyKeysItTouched() {
        final SequencedMap<String, DocumentValue> assignments = new LinkedHashMap<>();
        assignments.put("service.port", value("integer", "scalar", List.of("9090")));
        assignments.put("service.names", value("string", "collection", List.of("a", "b")));
        final DocumentValue.Mapping changed = assertInstanceOf(CommandHandler.Produced.class,
                new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.UPDATE,
                        Catalogue::new, permissive())
                        .run(update(assignments, List.of("service.legacy")), null, context()),
                "the change was refused").result();
        assertEquals(new DocumentValue.Whole(3),
                changed.member(UpdateConfigurationResult.CHANGED_PROPERTY_KEY_COUNT).orElseThrow(),
                "the answer does not say how many property keys the change touched");
        assertEquals(new DocumentValue.Text(SERVICE),
                changed.member(UpdateConfigurationResult.PERSISTENT_IDENTIFIER).orElseThrow());
        assertTrue(!String.valueOf(changed).contains("9090"),
                "the answer carries what the configuration now holds, which would mean reading"
                        + " every value back through a command nobody would audit: " + changed);
    }

    @Test
    @DisplayName("a search that would examine more than the caller may is refused, not trimmed")
    void asearchPastTheBudgetIsRefused() {
        final Catalogue wide = new Catalogue(ConfigurationHandlers.LOOKUP_BUDGET_EXCEEDED,
                "too many to enumerate");
        assertEquals(ConfigurationHandlers.LOOKUP_BUDGET_EXCEEDED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.SEARCH, () -> wide,
                                permissive()).run(new DocumentValue.Mapping(new LinkedHashMap<>()),
                                null, context()),
                        "a search the platform could not complete answered a shortened list, which"
                                + " reads as the complete answer").category());
    }

    @Test
    @DisplayName("a window takes a page of the matches and leaving one out takes them all")
    void awindowTakesAPage() {
        final List<ConfigurationCatalogue.Entry> three = List.of(
                new ConfigurationCatalogue.Entry("a", ConfigurationCatalogue.NOT_FROM_A_FACTORY, 1,
                        ConfigurationCatalogue.Binding.UNBOUND),
                new ConfigurationCatalogue.Entry("b", "a.factory", 1,
                        ConfigurationCatalogue.Binding.BOUND_TO_A_BUNDLE_LOCATION),
                new ConfigurationCatalogue.Entry("c", ConfigurationCatalogue.NOT_FROM_A_FACTORY, 1,
                        ConfigurationCatalogue.Binding.UNBOUND));
        assertEquals(1, ConfigurationHandler.pageOf(three, new ResultWindow.Initial(1, 1)).size(),
                "a window of one took something other than one match");
        assertEquals("b", ConfigurationHandler.pageOf(three,
                new ResultWindow.Initial(1, 1)).getFirst().persistentIdentifier(),
                "a window with an offset did not skip the matches before it");
        assertEquals(three, ConfigurationHandler.pageOf(three,
                new ResultWindow.Continuation("token")),
                "a continuation window took a page rather than continuing from where it left off");
        final DocumentValue.Mapping document = FindConfigurationsResult.documentOf(three, "next");
        assertTrue(document.member(FindConfigurationsResult.NEXT_CONTINUATION_TOKEN).isPresent(),
                "the answer does not carry the token reaching the next page");
        assertTrue(String.valueOf(document).contains("a.factory"),
                "a factory instance does not say which factory it came from");
    }

    @Test
    @DisplayName("an inspection and a removal the platform could not do are reported as it saying so")
    void aplatformFailureReachesEveryCommand() {
        final Catalogue refusing = new Catalogue(ConfigurationHandlers.LOOKUP_FAILED,
                "the service could not be asked");
        for (final ConfigurationHandler.Kind kind : List.of(ConfigurationHandler.Kind.INSPECTION,
                ConfigurationHandler.Kind.REMOVAL)) {
            assertEquals(ConfigurationHandlers.LOOKUP_FAILED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new ConfigurationHandler(CONTRACT, kind, () -> refusing, permissive())
                                    .run(identifier(SERVICE), null, context()),
                            kind + " reported a platform that could not be asked as an answer")
                            .category());
        }
    }

    @Test
    @DisplayName("an argument neither read nor write takes is refused before the platform is asked")
    void abadArgumentNeverReachesThePlatform() {
        final Catalogue catalogue = new Catalogue();
        final SequencedMap<String, DocumentValue> empty = new LinkedHashMap<>();
        assertEquals(ConfigurationHandlers.LOOKUP_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.INSPECTION,
                                () -> catalogue, permissive())
                                .run(new DocumentValue.Mapping(empty), null, context()),
                        "an argument naming no configuration reached the platform").category());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(UpdateConfigurationCommand.PERSISTENT_IDENTIFIER,
                new DocumentValue.Text(SERVICE));
        unknown.put("restart_after", new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        assertEquals(ConfigurationHandlers.LOOKUP_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.UPDATE,
                                () -> catalogue, permissive())
                                .run(new DocumentValue.Mapping(unknown), null, context()),
                        "a member nobody declared was accepted").category());
        assertEquals(List.of(), catalogue.calls(),
                "the platform was asked to act on an argument this build had already refused");
    }

    @Test
    @DisplayName("all four rows are the client's own and every handler declares exactly them")
    void allfourRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(FindConfigurationsCommand.WIRE_NAME,
                        ConfigurationHandlers.searchCategories()),
                Map.entry(ConfigurationIdentifierCommand.INSPECT_WIRE_NAME,
                        ConfigurationHandlers.inspectionCategories()),
                Map.entry(UpdateConfigurationCommand.WIRE_NAME,
                        ConfigurationHandlers.updateCategories()),
                Map.entry(ConfigurationIdentifierCommand.DELETE_WIRE_NAME,
                        ConfigurationHandlers.removalCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
        assertTrue(ConfigurationHandlers.updateCategories()
                        .containsAll(java.util.Arrays.stream(
                                        UpdateConfigurationCommand.Refusal.values())
                                .map(ConfigurationHandler::categoryFor).toList()),
                "a change refusal reaches a category this command's own row does not declare");
    }

    /** A catalogue that remembers what it was asked and answers from a fixed instance. */
    private static final class Catalogue implements ConfigurationCatalogue {

        private final List<String> asked = new ArrayList<>();
        private final List<Failed> refusal;

        Catalogue() {
            this(List.of());
        }

        Catalogue(String category, String detail) {
            this(List.of(new Failed(category, detail)));
        }

        private Catalogue(List<Failed> refusal) {
            this.refusal = List.copyOf(refusal);
        }

        List<String> calls() {
            return List.copyOf(asked);
        }

        @Override
        public Outcome find(String prefix, long budget) {
            asked.add("find");
            return refusal.isEmpty()
                    ? new Listed(List.of(new Entry(SERVICE, NOT_FROM_A_FACTORY, 2,
                            Binding.UNBOUND)))
                    : refusal.getFirst();
        }

        @Override
        public Outcome inspect(String persistentIdentifier) {
            asked.add("inspect");
            if (!refusal.isEmpty()) {
                return refusal.getFirst();
            }
            if (!SERVICE.equals(persistentIdentifier)) {
                return new Inspected(Presence.ABSENT, List.of());
            }
            return new Inspected(Presence.PRESENT, List.of(
                    new Property("service.port", ValueDisclosure.Evidence.NON_PASSWORD,
                            new ConfigurationValue("integer",
                                    ConfigurationValue.Cardinality.SCALAR, List.of("8080"))),
                    new Property(SECRET, ValueDisclosure.Evidence.PASSWORD,
                            new ConfigurationValue("string",
                                    ConfigurationValue.Cardinality.SCALAR, List.of("hunter2")))));
        }

        @Override
        public Outcome apply(String persistentIdentifier,
                              SequencedMap<String, ConfigurationValue> assignments,
                              List<String> removedPropertyKeys) {
            asked.add("update");
            return refusal.isEmpty()
                    ? new Changed(assignments.size() + removedPropertyKeys.size(),
                            Origin.SINGLETON)
                    : refusal.getFirst();
        }

        @Override
        public Outcome erase(String persistentIdentifier) {
            asked.add("delete");
            return refusal.isEmpty() ? new Changed(0, Origin.SINGLETON) : refusal.getFirst();
        }
    }

    private static CommandHandler.Answer run(ConfigurationHandler.Kind kind,
                                             DocumentValue.Mapping arguments) {
        return new ConfigurationHandler(CONTRACT, kind, Catalogue::new, permissive())
                .run(arguments, null, context());
    }

    private static PlatformControl permissive() {
        return PlatformControl.of("aem-6-5-lts", Set.of(ControlCapability.values()));
    }

    private static UpdateConfigurationCommand.Refused refusedUpdate(
            SequencedMap<String, DocumentValue> assignments, List<String> removed) {
        return assertInstanceOf(UpdateConfigurationCommand.Refused.class,
                UpdateConfigurationCommand.of(update(assignments, removed), CONTRACT),
                "an argument this command does not take was accepted");
    }

    private static DocumentValue.Mapping update(SequencedMap<String, DocumentValue> assignments,
                                                List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdateConfigurationCommand.PERSISTENT_IDENTIFIER,
                new DocumentValue.Text(SERVICE));
        if (!assignments.isEmpty()) {
            members.put(UpdateConfigurationCommand.ASSIGNMENTS,
                    new DocumentValue.Mapping(assignments));
        }
        if (!removed.isEmpty()) {
            members.put(UpdateConfigurationCommand.REMOVED_PROPERTY_KEYS,
                    new DocumentValue.Sequence(removed.stream()
                            .map(name -> (DocumentValue) new DocumentValue.Text(name)).toList()));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue value(String type, String cardinality, List<String> values) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(ConfigurationValue.TYPE, new DocumentValue.Text(type));
        held.put(ConfigurationValue.CARDINALITY, new DocumentValue.Text(cardinality));
        if (values.size() == 1 && "scalar".equals(cardinality)) {
            held.put(ConfigurationValue.VALUE, new DocumentValue.Text(values.getFirst()));
            return new DocumentValue.Mapping(held);
        }
        held.put(ConfigurationValue.VALUES, new DocumentValue.Sequence(values.stream()
                .map(item -> (DocumentValue) new DocumentValue.Text(item)).toList()));
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue.Mapping identifier(String persistentIdentifier) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ConfigurationIdentifierCommand.PERSISTENT_IDENTIFIER,
                new DocumentValue.Text(persistentIdentifier));
        return new DocumentValue.Mapping(members);
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_RESULT_BYTES)),
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
