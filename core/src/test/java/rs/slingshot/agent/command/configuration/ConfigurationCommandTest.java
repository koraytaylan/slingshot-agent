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
import rs.slingshot.agent.command.platform.ValueDisclosure;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The two commands about the platform's own configuration.
 *
 * <p>What is proved here is the shape of the boundary. Both only read, and pass through no control
 * gate, because an environment an operator cannot alter is exactly the one where knowing what it
 * says matters most.</p>
 *
 * <p>And no listing carries a value. A search across a whole instance is the call whose output ends
 * up pasted into a ticket, so what it carries has to be safe to paste.</p>
 */
final class ConfigurationCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String SERVICE = "rs.slingshot.Service";

    private static final String SECRET = "service.password";

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
    @DisplayName("a search that would examine more than the caller may is refused, not trimmed")
    void asearchPastTheBudgetIsRefused() {
        final Catalogue wide = new Catalogue(ConfigurationHandlers.LOOKUP_BUDGET_EXCEEDED,
                "too many to enumerate");
        assertEquals(ConfigurationHandlers.LOOKUP_BUDGET_EXCEEDED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.SEARCH, () -> wide)
                                .run(new DocumentValue.Mapping(new LinkedHashMap<>()), null, context()),
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
    @DisplayName("an inspection the platform could not do is reported as it saying so")
    void aplatformFailureReachesTheInspection() {
        final Catalogue refusing = new Catalogue(ConfigurationHandlers.LOOKUP_FAILED,
                "the service could not be asked");
        assertEquals(ConfigurationHandlers.LOOKUP_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.INSPECTION,
                                () -> refusing).run(identifier(SERVICE), null, context()),
                        "an inspection reported a platform that could not be asked as an answer")
                        .category());
    }

    @Test
    @DisplayName("an argument the inspection does not take is refused before the platform is asked")
    void abadArgumentNeverReachesThePlatform() {
        final Catalogue catalogue = new Catalogue();
        final SequencedMap<String, DocumentValue> empty = new LinkedHashMap<>();
        assertEquals(ConfigurationHandlers.LOOKUP_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.INSPECTION,
                                () -> catalogue)
                                .run(new DocumentValue.Mapping(empty), null, context()),
                        "an argument naming no configuration reached the platform").category());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(ConfigurationIdentifierCommand.PERSISTENT_IDENTIFIER,
                new DocumentValue.Text(SERVICE));
        unknown.put("restart_after", new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        assertEquals(ConfigurationHandlers.LOOKUP_FAILED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.INSPECTION,
                                () -> catalogue)
                                .run(new DocumentValue.Mapping(unknown), null, context()),
                        "a member nobody declared was accepted").category());
        assertEquals(List.of(), catalogue.calls(),
                "the platform was asked to act on an argument this build had already refused");
    }

    @Test
    @DisplayName("both rows are the client's own and every handler declares exactly them")
    void bothRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(FindConfigurationsCommand.WIRE_NAME,
                        ConfigurationHandlers.searchCategories()),
                Map.entry(ConfigurationIdentifierCommand.INSPECT_WIRE_NAME,
                        ConfigurationHandlers.inspectionCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
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
    }

    private static CommandHandler.Answer run(ConfigurationHandler.Kind kind,
                                             DocumentValue.Mapping arguments) {
        return new ConfigurationHandler(CONTRACT, kind, Catalogue::new)
                .run(arguments, null, context());
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
