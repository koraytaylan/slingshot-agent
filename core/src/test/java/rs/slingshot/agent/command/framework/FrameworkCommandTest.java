// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.BundleState;
import rs.slingshot.agent.command.platform.ComponentState;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The three commands about the framework this agent runs inside.
 *
 * <p>What is proved here is the gap between the two listings. A bundle can be active while the
 * component inside it never activated, and every answer this build gives has to keep those apart —
 * because "the bundle is installed but the feature does not work" is the report, and the bundle
 * listing alone says nothing about it.</p>
 */
final class FrameworkCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String IMMUTABLE = "aem-cloud-service";

    private static final String BUNDLE = "rs.slingshot.agent.core";

    @Test
    @DisplayName("a listing reports every state by default, because a filter hides the broken one")
    void alistingReportsEveryStateByDefault() {
        final ListBundlesCommand held = assertInstanceOf(ListBundlesCommand.Held.class,
                ListBundlesCommand.of(new DocumentValue.Mapping(new LinkedHashMap<>()), CONTRACT),
                "a listing naming nothing was refused").command();
        assertEquals(List.of(), held.states(),
                "a listing naming no state was read as naming one, and a caller filtered to active"
                        + " would see nothing wrong with an instance whose problem is a bundle"
                        + " that never resolved");
        assertEquals(ListBundlesCommand.EVERY_BUNDLE, held.prefix());
    }

    @Test
    @DisplayName("a bundle listing reports what each bundle is and never what it was configured with")
    void abundleListingCarriesNoConfiguration() {
        final DocumentValue.Mapping listed = assertInstanceOf(CommandHandler.Produced.class,
                run(FrameworkHandler.Kind.BUNDLES, new DocumentValue.Mapping(new LinkedHashMap<>())),
                "the listing was refused").result();
        final DocumentValue.Sequence matches = assertInstanceOf(DocumentValue.Sequence.class,
                listed.member(FrameworkResults.MATCHES).orElseThrow());
        final DocumentValue.Mapping first = assertInstanceOf(DocumentValue.Mapping.class,
                matches.items().getFirst());
        assertEquals(new DocumentValue.Text(BUNDLE),
                first.member(FrameworkResults.SYMBOLIC_NAME).orElseThrow());
        assertEquals(new DocumentValue.Text("active"),
                first.member(FrameworkResults.STATE).orElseThrow());
        assertTrue(first.member(FrameworkResults.VERSION).isPresent()
                        && first.member(FrameworkResults.BUNDLE_IDENTIFIER).isPresent(),
                "a bundle listing does not say which version is installed or what the framework"
                        + " calls it, which are the two things an operator compares between"
                        + " environments");
        assertTrue(!String.valueOf(listed).contains("service.password"),
                "a bundle listing carries what a bundle was configured with, which has its own"
                        + " command and its own disclosure rule: " + listed);
    }

    @Test
    @DisplayName("installed and resolved are told apart, because they send an operator to different places")
    void thetwoStoppedStatesAreDistinct() {
        assertEquals(Optional.of(BundleState.INSTALLED), BundleState.named("installed"));
        assertEquals(Optional.of(BundleState.RESOLVED), BundleState.named("resolved"));
        assertTrue(BundleState.INSTALLED != BundleState.RESOLVED,
                "a bundle the framework cannot satisfy and one it has not started are the same"
                        + " value, and reporting either as not running sends half of them to the"
                        + " wrong place");
        assertEquals(6, BundleState.values().length);
        assertEquals(Optional.empty(), BundleState.named("broken"));
    }

    @Test
    @DisplayName("a component listing reports the unsatisfied state the bundle listing cannot show")
    void acomponentListingShowsWhatABundleListingCannot() {
        final DocumentValue.Mapping listed = assertInstanceOf(CommandHandler.Produced.class,
                run(FrameworkHandler.Kind.COMPONENTS,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                "the listing was refused").result();
        assertTrue(String.valueOf(listed).contains("unsatisfied"),
                "a component whose requirement is missing was not reported as unsatisfied, which"
                        + " is the state behind most of the reports this command exists for: "
                        + listed);
        final DocumentValue.Mapping first = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Sequence.class,
                        listed.member(FrameworkResults.MATCHES).orElseThrow()).items().getFirst());
        assertEquals(new DocumentValue.Text(BUNDLE),
                first.member(FrameworkResults.BUNDLE_SYMBOLIC_NAME).orElseThrow(),
                "a component does not say which bundle declares it, so an operator cannot tell"
                        + " which artifact to go and look at");
        assertTrue(first.member(FrameworkResults.SERVICE_PERSISTENT_IDENTIFIER).isEmpty(),
                "a component that takes no configuration was given a configuration identifier");
    }

    @Test
    @DisplayName("a state nobody publishes is refused rather than ignored, in both listings")
    void anunknownStateIsRefused() {
        final SequencedMap<String, DocumentValue> bundles = new LinkedHashMap<>();
        bundles.put(ListBundlesCommand.STATES, new DocumentValue.Sequence(
                List.of(new DocumentValue.Text("broken"))));
        assertEquals(ListBundlesCommand.Refusal.STATE_REJECTED,
                assertInstanceOf(ListBundlesCommand.Refused.class,
                        ListBundlesCommand.of(new DocumentValue.Mapping(bundles), CONTRACT),
                        "a state nobody publishes was accepted, and a caller who misspelled one"
                                + " would receive an empty list and believe it").refusal());
        final SequencedMap<String, DocumentValue> components = new LinkedHashMap<>();
        components.put(ListComponentsCommand.STATES, new DocumentValue.Sequence(
                List.of(new DocumentValue.Text("resolved"))));
        assertEquals(ListComponentsCommand.Refusal.STATE_REJECTED,
                assertInstanceOf(ListComponentsCommand.Refused.class,
                        ListComponentsCommand.of(new DocumentValue.Mapping(components), CONTRACT),
                        "a bundle's state was accepted as a component's, and the two sets look"
                                + " similar and are not the same question").refusal());
        assertEquals(4, ComponentState.values().length);
        assertEquals(Optional.empty(), ComponentState.named("resolved"));
    }

    @Test
    @DisplayName("a deployment whose bundle state comes from its image refuses the transition")
    void animmutableDeploymentRefusesTheTransition() {
        final Inventory inventory = new Inventory();
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new FrameworkHandler(CONTRACT, FrameworkHandler.Kind.TRANSITION, inventory,
                        PlatformControl.of(IMMUTABLE, Set.of()))
                        .run(transition(BUNDLE, "stop"), null, context()),
                "a bundle was stopped on a deployment whose bundle state comes from its image");
        assertEquals(PlatformControl.NOT_PERMITTED, refused.category());
        assertEquals(List.of(), inventory.calls(),
                "the framework was asked to stop a bundle on a deployment where that lasts until"
                        + " the next container replaces it");
        assertInstanceOf(CommandHandler.Produced.class,
                new FrameworkHandler(CONTRACT, FrameworkHandler.Kind.BUNDLES, inventory,
                        PlatformControl.of(IMMUTABLE, Set.of()))
                        .run(new DocumentValue.Mapping(new LinkedHashMap<>()), null, context()),
                "listing bundles was refused on a deployment that will not let one be stopped,"
                        + " and a deployment that will not let a bundle be stopped still knows"
                        + " perfectly well which ones are running");
    }

    @Test
    @DisplayName("a transition reports where the bundle ended up rather than where it was asked to go")
    void atransitionReportsWhereItEndedUp() {
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                run(FrameworkHandler.Kind.TRANSITION, transition(BUNDLE, "start")),
                "the transition was refused").result();
        assertEquals(new DocumentValue.Text("resolved"),
                moved.member(FrameworkResults.OBSERVED_STATE).orElseThrow(),
                "a bundle asked to start and left resolved was reported as started, and that is"
                        + " precisely the case an operator needs told: one of its components would"
                        + " not activate");
        assertEquals(new DocumentValue.Text(BUNDLE),
                moved.member(FrameworkResults.SYMBOLIC_NAME).orElseThrow());
    }

    @Test
    @DisplayName("refreshing is named rather than something starting quietly does")
    void refreshingIsItsOwnTransition() {
        assertEquals(List.of("start", "stop", "refresh"),
                BundleInventory.Transition.spellings(),
                "the set of transitions changed, and refreshing restarts everything wired to a"
                        + " bundle — on an author instance that can be most of them");
        assertEquals(SetBundleStateCommand.Refusal.TRANSITION_REJECTED,
                assertInstanceOf(SetBundleStateCommand.Refused.class,
                        SetBundleStateCommand.of(transition(BUNDLE, "restart"), CONTRACT),
                        "a transition nobody publishes was accepted").refusal());
        assertEquals(SetBundleStateCommand.Refusal.MEMBER_ABSENT,
                assertInstanceOf(SetBundleStateCommand.Refused.class,
                        SetBundleStateCommand.of(new DocumentValue.Mapping(new LinkedHashMap<>()),
                                CONTRACT), "an argument naming neither was accepted").refusal());
        assertEquals(SetBundleStateCommand.Refusal.NOT_A_DOCUMENT,
                assertInstanceOf(SetBundleStateCommand.Refused.class,
                        SetBundleStateCommand.of(new DocumentValue.Text(BUNDLE), CONTRACT),
                        "text was accepted as an argument").refusal());
    }

    @Test
    @DisplayName("the framework refusing a transition is told apart from the deployment refusing it")
    void thetwoRefusalsAreDistinct() {
        final Inventory refusing = new Inventory();
        refusing.refuse(FrameworkHandler.TRANSITION_REFUSED, "a dependency is missing");
        assertEquals(FrameworkHandler.TRANSITION_REFUSED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new FrameworkHandler(CONTRACT, FrameworkHandler.Kind.TRANSITION, refusing,
                                permissive()).run(transition(BUNDLE, "start"), null, context()),
                        "a transition the framework would not make was reported as done")
                        .category(),
                "the deployment refusing the control and the framework refusing this bundle were"
                        + " reported the same way, and they send an operator to different places");
    }

    @Test
    @DisplayName("a listing past the caller's own budget is refused rather than shortened")
    void alistingPastTheBudgetIsRefused() {
        final CallerContext narrow = contextWith(new Budget(Budget.Kind.DISCOVERY, 1));
        assertEquals(FrameworkHandler.DISCOVERY_BUDGET_EXCEEDED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new FrameworkHandler(CONTRACT, FrameworkHandler.Kind.BUNDLES,
                                new Inventory(), permissive())
                                .run(new DocumentValue.Mapping(new LinkedHashMap<>()), null,
                                        narrow),
                        "a listing past the caller's budget answered a shortened list, which reads"
                                + " as the complete answer").category());
        assertEquals(1, FrameworkHandler.pageOf(List.of("a", "b", "c"),
                new ResultWindow.Initial(1, 1)).size());
        assertEquals(List.of("a", "b", "c"), FrameworkHandler.pageOf(List.of("a", "b", "c"),
                new ResultWindow.Continuation("token")));
    }

    @Test
    @DisplayName("all three rows are the client's own and every handler declares exactly them")
    void allthreeRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(ListBundlesCommand.WIRE_NAME, FrameworkHandler.listingCategories(
                        FrameworkHandler.BUNDLE_INVENTORY_FAILED)),
                Map.entry(ListComponentsCommand.WIRE_NAME, FrameworkHandler.listingCategories(
                        FrameworkHandler.COMPONENT_INVENTORY_FAILED)),
                Map.entry(SetBundleStateCommand.WIRE_NAME,
                        FrameworkHandler.transitionCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
        assertEquals(RegistryRow.OperationKey.REQUIRED,
                row(SetBundleStateCommand.WIRE_NAME).operationKey(),
                "stopping a bundle twice is not stopping it once, and this row no longer requires"
                        + " a key");
        assertEquals(RegistryRow.OperationKey.REFUSED,
                row(ListBundlesCommand.WIRE_NAME).operationKey());
    }

    /** An inventory that remembers what it was asked and answers from a fixed framework. */
    private static final class Inventory implements BundleInventory {

        private final List<String> asked = new java.util.ArrayList<>();
        private final List<Refused> refusal = new java.util.ArrayList<>();

        void refuse(String category, String detail) {
            refusal.add(new Refused(category, detail));
        }

        List<String> calls() {
            return List.copyOf(asked);
        }

        @Override
        public Outcome bundles(String prefix, List<BundleState> states) {
            asked.add("bundles");
            return refusal.isEmpty()
                    ? new Bundles(List.of(
                            new BundleEntry(1, BUNDLE, "0.1.0", BundleState.ACTIVE),
                            new BundleEntry(2, "org.example.other", "2.0.0",
                                    BundleState.RESOLVED)))
                    : refusal.getFirst();
        }

        @Override
        public Outcome components(String prefix, List<ComponentState> states) {
            asked.add("components");
            return refusal.isEmpty()
                    ? new Components(List.of(
                            new ComponentEntry("rs.slingshot.agent.Servlet", BUNDLE,
                                    TAKES_NO_SERVICE, ComponentState.UNSATISFIED)))
                    : refusal.getFirst();
        }

        @Override
        public Outcome transition(String symbolicName, Transition transition) {
            asked.add("transition");
            return refusal.isEmpty()
                    ? new Transitioned(BundleState.RESOLVED) : refusal.getFirst();
        }
    }

    private static CommandHandler.Answer run(FrameworkHandler.Kind kind,
                                             DocumentValue.Mapping arguments) {
        return new FrameworkHandler(CONTRACT, kind, new Inventory(), permissive())
                .run(arguments, null, context());
    }

    private static PlatformControl permissive() {
        return PlatformControl.of("aem-6-5-lts", Set.of(ControlCapability.values()));
    }

    private static DocumentValue.Mapping transition(String symbolicName, String transition) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SetBundleStateCommand.SYMBOLIC_NAME, new DocumentValue.Text(symbolicName));
        members.put(SetBundleStateCommand.TRANSITION, new DocumentValue.Text(transition));
        return new DocumentValue.Mapping(members);
    }

    private static CallerContext context() {
        return contextWith(Budget.discovery(CONTRACT));
    }

    private static CallerContext contextWith(Budget discovery) {
        return new CallerContext(operation(), discovery, Budget.time(CONTRACT),
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
