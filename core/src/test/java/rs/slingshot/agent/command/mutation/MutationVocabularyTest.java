// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.AccessClass;
import rs.slingshot.agent.command.ExecutionClass;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The vocabulary twenty mutations share, proved once so the twentieth behaves like the first.
 *
 * <p>What is proved here is mostly what is refused. A mutation that accepts a malformed guard does
 * not fail — it does something, to somebody's repository, that nobody chose.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class MutationVocabularyTest {

    private static final AgentContract CONTRACT = contract();

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a reference policy has two values, no default, and names both when refused")
    void areferencePolicyHasNoDefault() {
        assertEquals(2, ReferencePolicy.values().length,
                "a third answer appeared between refusing and going ahead, and anything between"
                        + " them is a partial deletion");
        for (final ReferencePolicy policy : ReferencePolicy.values()) {
            assertEquals(policy, ReferencePolicy.named(policy.spelling()).orElseThrow(),
                    policy + " does not name itself back from its own spelling");
        }
        assertEquals(java.util.Optional.empty(), ReferencePolicy.named("warn_and_continue"),
                "a policy this contract does not have was read as one");
        assertEquals(List.of("ignore_references", "refuse_when_referenced"),
                ReferencePolicy.spellings(),
                "the spellings are not the client's own, so a caller sending theirs is refused");
    }

    @Test
    @DisplayName("a property named in both lists is refused rather than resolved")
    void anameInBothListsIsRefused() {
        final PropertyChange.Refused refused = assertInstanceOf(PropertyChange.Refused.class,
                PropertyChange.of(change(single("jcr:title", "A title"), List.of("jcr:title")),
                        CONTRACT),
                "a property named as both a write and a removal was accepted");
        assertEquals(PropertyChange.Refusal.NAME_IN_BOTH_LISTS, refused.refusal());
        assertTrue(refused.detail().contains("jcr:title"), refused.detail());
    }

    @Test
    @DisplayName("a property in neither list is neither written nor removed")
    void apropertyInNeitherListIsUntouched() {
        final PropertyChange change = held(change(single("jcr:title", "A title"),
                List.of("jcr:description")));
        assertEquals(List.of("jcr:title"), List.copyOf(change.set().keySet()));
        assertEquals(List.of("jcr:description"), List.copyOf(change.removed()));
        assertTrue(!change.set().containsKey("cq:template"),
                "a property nobody named is in the write list");
        assertTrue(!change.removed().contains("cq:template"),
                "a property nobody named is in the removal list, so an update carrying a partial"
                        + " view would destroy the rest of the node");
        assertTrue(held(change(new LinkedHashMap<>(), List.of())).isEmpty(),
                "an update naming neither list was read as changing something");
    }

    @Test
    @DisplayName("a value says how many values it holds, and a list of one stays a list")
    void acardinalityIsStatedRatherThanInferred() {
        final PropertyChange one = held(change(single("jcr:title", "A title"), List.of()));
        assertInstanceOf(PropertyValue.Single.class, one.set().get("jcr:title"));
        final PropertyChange several = held(change(multiple("cq:tags", "a", "b"), List.of()));
        assertInstanceOf(PropertyValue.Multiple.class, several.set().get("cq:tags"));
        final PropertyChange listOfOne = held(change(multiple("cq:tags", "a"), List.of()));
        assertInstanceOf(PropertyValue.Multiple.class, listOfOne.set().get("cq:tags"),
                "a list holding one value was read as a single value, and a repository tells those"
                        + " apart even where this reader does not");
        assertEquals(1, listOfOne.set().get("cq:tags").values().size());
        // Both shapes answer what is being written the same way, so a handler writing a property
        // does not branch on which shape it was given — the shape decides what the repository is
        // told, not what this side has to remember to ask.
        assertEquals(List.of("A title"), one.set().get("jcr:title").values().stream()
                        .map(PropertyScalar::value)
                        .toList(),
                "a single value did not answer the one value it holds");
        assertEquals(List.of("a", "b"), several.set().get("cq:tags").values().stream()
                        .map(PropertyScalar::value)
                        .toList());
        assertEquals(ScalarKind.STRING, one.set().get("jcr:title").values().getFirst().kind(),
                "a written value lost the kind that says what it is");
    }

    @Test
    @DisplayName("a value carrying what its cardinality does not take is refused")
    void acardinalityTakesWhatItDeclares() {
        final SequencedMap<String, DocumentValue> confused = new LinkedHashMap<>();
        confused.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        confused.put(PropertyValue.VALUES, new DocumentValue.Sequence(List.of(scalar("a"))));
        assertEquals(PropertyValue.Refusal.FIELDS_DO_NOT_MATCH_CARDINALITY,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(confused), CONTRACT),
                        "a single value carrying a list was accepted").refusal());
        final SequencedMap<String, DocumentValue> empty = new LinkedHashMap<>();
        empty.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.MULTIPLE));
        empty.put(PropertyValue.VALUES, new DocumentValue.Sequence(List.of()));
        assertEquals(PropertyValue.Refusal.VALUES_EMPTY,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(empty), CONTRACT),
                        "a list of no values was accepted, and a property with none is one"
                                + " removed").refusal());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(PropertyValue.CARDINALITY, new DocumentValue.Text("some"));
        assertEquals(PropertyValue.Refusal.UNKNOWN_CARDINALITY,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(unknown), CONTRACT),
                        "a cardinality this contract does not have was accepted").refusal());
        assertEquals(PropertyValue.Refusal.NOT_A_DOCUMENT,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Text("A title"), CONTRACT),
                        "a bare string was read as a property value, so its cardinality would"
                                + " have been inferred").refusal());
        final SequencedMap<String, DocumentValue> bareList = new LinkedHashMap<>();
        bareList.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.MULTIPLE));
        bareList.put(PropertyValue.VALUES, new DocumentValue.Text("a"));
        assertEquals(PropertyValue.Refusal.FIELDS_DO_NOT_MATCH_CARDINALITY,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(bareList), CONTRACT),
                        "a list that is not a list was accepted").refusal());
        final SequencedMap<String, DocumentValue> untyped = new LinkedHashMap<>();
        untyped.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        untyped.put(PropertyValue.VALUE, new DocumentValue.Text("A title"));
        assertEquals(PropertyValue.Refusal.SCALAR_REJECTED,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(untyped), CONTRACT),
                        "a value with no kind beside it was accepted").refusal());
        final SequencedMap<String, DocumentValue> untypedItem = new LinkedHashMap<>();
        untypedItem.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.MULTIPLE));
        untypedItem.put(PropertyValue.VALUES,
                new DocumentValue.Sequence(List.of(new DocumentValue.Text("a"))));
        assertEquals(PropertyValue.Refusal.SCALAR_REJECTED,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(untypedItem), CONTRACT),
                        "a list item with no kind beside it was accepted").refusal());
        final long items = CONTRACT.value(ContractLimit.MAXIMUM_PROPERTY_VALUE_ITEMS);
        final SequencedMap<String, DocumentValue> past = new LinkedHashMap<>();
        past.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.MULTIPLE));
        past.put(PropertyValue.VALUES, new DocumentValue.Sequence(
                java.util.stream.IntStream.range(0, (int) items + 1)
                        .mapToObj(index -> scalar("value-" + index))
                        .toList()));
        assertEquals(PropertyValue.Refusal.VALUES_TOO_MANY,
                assertInstanceOf(PropertyValue.Refused.class,
                        PropertyValue.of(new DocumentValue.Mapping(past), CONTRACT),
                        "a list past the bound was accepted").refusal());
    }

    @Test
    @DisplayName("both property bounds are proved at their value and one past it")
    void bothpropertyBoundsAreProved() {
        final long properties = CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_PROPERTIES);
        assertEquals(properties, held(change(many((int) properties), List.of())).set().size(),
                "a change at the bound was not read whole");
        assertEquals(PropertyChange.Refusal.TOO_MANY_PROPERTIES,
                refusal(change(many((int) properties + 1), List.of())).refusal());
        final long removals = CONTRACT.value(ContractLimit.MAXIMUM_REMOVED_PROPERTY_NAMES);
        assertEquals(removals,
                held(change(new LinkedHashMap<>(), names((int) removals))).removed().size());
        assertEquals(PropertyChange.Refusal.TOO_MANY_REMOVALS,
                refusal(change(new LinkedHashMap<>(), names((int) removals + 1))).refusal());
        final long name = CONTRACT.value(ContractLimit.MAXIMUM_PROPERTY_NAME_BYTES);
        assertEquals(PropertyChange.Refusal.NAME_TOO_LONG,
                refusal(change(single("n".repeat((int) name + 1), "A"), List.of())).refusal());
    }

    @Test
    @DisplayName("a placement names a neighbour or asks for the end, and never an index")
    void aplacementIsANeighbourRatherThanAnIndex() {
        assertEquals("teaser", ((ComponentPlacement.Before) placement(placementBefore("teaser")))
                .siblingName());
        assertInstanceOf(ComponentPlacement.Last.class, placement(placementLast()));
        assertEquals(java.util.Optional.of("teaser"),
                ComponentPlacement.siblingOf(placement(placementBefore("teaser"))));
        assertEquals(java.util.Optional.empty(),
                ComponentPlacement.siblingOf(placement(placementLast())),
                "asking for the end reported a neighbour it does not have");
        assertEquals(ComponentPlacement.Refusal.SIBLING_REJECTED,
                placementRefusal(placementBefore("  ")).refusal(),
                "a placement before nothing in particular was accepted; the end is its own shape");
        final SequencedMap<String, DocumentValue> both = new LinkedHashMap<>();
        both.put(ComponentPlacement.MODE, new DocumentValue.Text(ComponentPlacement.LAST_MODE));
        both.put(ComponentPlacement.SIBLING_NAME, new DocumentValue.Text("teaser"));
        assertEquals(ComponentPlacement.Refusal.FIELDS_DO_NOT_MATCH_MODE,
                placementRefusal(new DocumentValue.Mapping(both)).refusal(),
                "a placement asking for the end and naming a neighbour was accepted, so one of the"
                        + " two was silently ignored");
        assertEquals(ComponentPlacement.Refusal.UNKNOWN_MODE,
                placementRefusal(new DocumentValue.Mapping(new LinkedHashMap<>())).refusal());
    }

    @Test
    @DisplayName("the three outcomes are mutually exclusive, which is a fact about their shapes")
    void thethreeOutcomesCannotOverlap() {
        // Asserted over what the types carry rather than by testing one instance against another:
        // the compiler already knows a refusal is not a change, so an instanceof would prove
        // nothing. What can go wrong is somebody giving the unknown outcome a result, or adding a
        // fourth shape — and that is what this notices.
        assertEquals(List.of("Changed", "Refused", "Unknown"),
                java.util.Arrays.stream(MutationOutcome.class.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .sorted()
                        .toList(),
                "the three answers a mutation can give are no longer three");
        assertEquals(List.of("result"), componentsOf(MutationOutcome.Changed.class),
                "the answer that says something changed carries something other than what changed");
        assertEquals(List.of("category", "detail"), componentsOf(MutationOutcome.Refused.class),
                "a refusal carries a result, so a caller told it failed can still read one");
        assertEquals(List.of("detail"), componentsOf(MutationOutcome.Unknown.class),
                "the unknown outcome carries a claim about whether anything changed, and its whole"
                        + " meaning is that this side cannot say");
        assertEquals("the acknowledgement was lost",
                new MutationOutcome.Unknown("the acknowledgement was lost").detail());
    }

    private static List<String> componentsOf(Class<? extends MutationOutcome> shape) {
        return java.util.Arrays.stream(shape.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("how many commits a command owes is read from its row rather than its package")
    void theexpectationComesFromTheRow() {
        assertEquals(SingleCommit.Expectation.ONE_COMMIT,
                SingleCommit.expectationOf(row(SingleCommit.OUTCOME_UNKNOWN)).orElseThrow());
        assertEquals(SingleCommit.Expectation.NO_COMMIT,
                SingleCommit.expectationOf(row(SingleCommit.ADMISSION_OUTCOME_UNKNOWN))
                        .orElseThrow(),
                "an admission was made to owe a repository commit, which is a write nobody asked"
                        + " for");
        assertEquals(SingleCommit.Expectation.NO_COMMIT,
                SingleCommit.expectationOf(row(SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN))
                        .orElseThrow());
        assertEquals(java.util.Optional.empty(), SingleCommit.expectationOf(row("not_found")),
                "a row declaring no unknown outcome was given an expectation anyway, so a read"
                        + " would be held to a commit rule");
    }

    @Test
    @DisplayName("a second commit is refused, whatever the row declares")
    void asecondCommitIsRefused() {
        for (final String category : List.of(SingleCommit.OUTCOME_UNKNOWN,
                SingleCommit.ADMISSION_OUTCOME_UNKNOWN)) {
            final SingleCommit.Refused refused = assertInstanceOf(SingleCommit.Refused.class,
                    SingleCommit.around(SingleCommit.expectationOf(row(category)).orElseThrow(),
                            resolver(), session -> {
                                commit(session);
                                commit(session);
                                return new MutationOutcome.Changed(
                                        new DocumentValue.Mapping(new LinkedHashMap<>()));
                            }),
                    "a handler committing twice was accepted under " + category);
            assertEquals(SingleCommit.Breach.COMMITTED_TWICE, refused.breach());
            assertEquals(2, refused.commits(),
                    "the second commit was not counted, so a handler that swallowed the refusal"
                            + " could hide it");
        }
    }

    @Test
    @DisplayName("a change with no commit, and a commit with no change, are each refused")
    void whatarowOwesIsEnforcedBothWays() {
        assertEquals(SingleCommit.Breach.CHANGED_WITHOUT_COMMITTING,
                breach(SingleCommit.OUTCOME_UNKNOWN, session ->
                        new MutationOutcome.Changed(
                                new DocumentValue.Mapping(new LinkedHashMap<>()))),
                "a handler reported a change and committed nothing, so what it reported is not in"
                        + " the repository");
        assertEquals(SingleCommit.Breach.COMMITTED_WHILE_REFUSING,
                breach(SingleCommit.OUTCOME_UNKNOWN, session -> {
                    commit(session);
                    return new MutationOutcome.Refused("page_not_found", "not there");
                }),
                "a handler refused and committed anyway, so a failure left something behind");
        assertEquals(SingleCommit.Breach.COMMITTED_WITHOUT_OWING,
                breach(SingleCommit.ADMISSION_OUTCOME_UNKNOWN, session -> {
                    commit(session);
                    return new MutationOutcome.Changed(
                            new DocumentValue.Mapping(new LinkedHashMap<>()));
                }),
                "an admission committed to the caller's repository, and what it changes is not the"
                        + " caller's repository");
        assertInstanceOf(SingleCommit.Ran.class,
                SingleCommit.around(SingleCommit.Expectation.NO_COMMIT, resolver(),
                        session -> new MutationOutcome.Changed(
                                new DocumentValue.Mapping(new LinkedHashMap<>()))),
                "an admission that committed nothing was refused");
    }

    @Test
    @DisplayName("an unknown outcome is held to no commit count, which is what makes it honest")
    void anunknownOutcomeIsHeldToNothing() {
        for (final long committed : List.of(0L, 1L)) {
            assertInstanceOf(SingleCommit.Ran.class,
                    SingleCommit.around(SingleCommit.Expectation.ONE_COMMIT, resolver(),
                            session -> {
                                if (committed > 0) {
                                    commit(session);
                                }
                                return new MutationOutcome.Unknown("the answer never came back");
                            }),
                    "an unknown outcome after " + committed + " commits was refused, and the whole"
                            + " meaning of it is that this side cannot say whether one landed");
        }
    }

    @Test
    @DisplayName("each of the three answers reaches dispatch as itself, and a breach as a failure")
    void everyanswerReachesDispatchAsItself() {
        final DocumentValue.Mapping result = new DocumentValue.Mapping(new LinkedHashMap<>());
        assertInstanceOf(rs.slingshot.agent.command.CommandHandler.Produced.class,
                MutationAnswer.of(new SingleCommit.Ran(new MutationOutcome.Changed(result)),
                        "repository_commit_failed", SingleCommit.OUTCOME_UNKNOWN),
                "a change that happened did not reach the caller as a result");
        assertEquals("page_not_found",
                ((rs.slingshot.agent.command.CommandHandler.Failed) MutationAnswer.of(
                        new SingleCommit.Ran(new MutationOutcome.Refused("page_not_found", "gone")),
                        "repository_commit_failed", SingleCommit.OUTCOME_UNKNOWN)).category());
        // The one that matters: an outcome nobody knows keeps its own category rather than
        // becoming an ordinary failure, because a caller told a write failed retries it and a
        // retry of a write that landed is a second effect on their repository.
        assertEquals(SingleCommit.OUTCOME_UNKNOWN,
                ((rs.slingshot.agent.command.CommandHandler.Failed) MutationAnswer.of(
                        new SingleCommit.Ran(new MutationOutcome.Unknown("no answer came back")),
                        "repository_commit_failed", SingleCommit.OUTCOME_UNKNOWN)).category(),
                "an outcome nobody knows was flattened into an ordinary failure");
        // And which unknown it is comes from the caller rather than from this mapping. There are
        // three, and a command that changes a queue rather than a repository owes a different one;
        // hard-coding the repository's here is how an admission would answer a category its own
        // registry row has never declared.
        assertEquals(SingleCommit.ADMISSION_OUTCOME_UNKNOWN,
                ((rs.slingshot.agent.command.CommandHandler.Failed) MutationAnswer.of(
                        new SingleCommit.Ran(new MutationOutcome.Unknown("no answer came back")),
                        "admission_rejected",
                        SingleCommit.ADMISSION_OUTCOME_UNKNOWN)).category(),
                "an admission nobody heard back about was reported under the repository"
                        + " mutation's unknown category, which its own row does not declare");
        final var breached = (rs.slingshot.agent.command.CommandHandler.Failed) MutationAnswer.of(
                new SingleCommit.Refused(SingleCommit.Breach.COMMITTED_TWICE, 2),
                "repository_commit_failed", SingleCommit.OUTCOME_UNKNOWN);
        assertEquals("repository_commit_failed", breached.category(),
                "a broken commit rule reached the caller as something other than the commit"
                        + " failing, and from their side that is exactly what it is");
        assertTrue(breached.detail().contains("COMMITTED_TWICE"), breached.detail());
    }

    @Test
    @DisplayName("a delete's answer says what went and how much of it there was")
    void adeleteSaysWhatWent() {
        final DocumentValue.Mapping answered =
                DeletedResourceResult.documentOf("/content/site/page", 9);
        assertEquals(new DocumentValue.Text("/content/site/page"),
                answered.member(DeletedResourceResult.REPOSITORY_PATH).orElseThrow());
        assertEquals(new DocumentValue.Whole(9),
                answered.member(DeletedResourceResult.REMOVED_NODE_COUNT).orElseThrow(),
                "a caller who asked to delete one page is not told how much went with it");
    }

    private static SingleCommit.Breach breach(String category, SingleCommit.Mutation mutation) {
        return assertInstanceOf(SingleCommit.Refused.class,
                SingleCommit.around(SingleCommit.expectationOf(row(category)).orElseThrow(),
                        new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK)
                                .resourceResolver(),
                        mutation),
                "the run was accepted").breach();
    }

    private static void commit(ResourceResolver session) {
        try {
            session.commit();
        } catch (final PersistenceException refused) {
            // Counted whether or not it went through: what this suite is about is how many were
            // attempted, and a handler that swallowed the refusal is exactly the case being proved.
            assertTrue(refused.getMessage().contains(CountingResolver.REFUSAL),
                    "the commit was refused for a reason this suite did not arrange: "
                            + refused.getMessage());
        }
    }

    private ResourceResolver resolver() {
        return sling.resourceResolver();
    }

    private static RegistryRow row(String category) {
        return new RegistryRow("a_command", "1.0.0", AccessClass.WRITE,
                RegistryRow.OperationKey.REQUIRED, 16384, List.of(category), "a".repeat(64),
                "b".repeat(64), "c".repeat(64), 0, ExecutionClass.IMMEDIATE);
    }

    private static PropertyChange held(DocumentValue.Mapping arguments) {
        return assertInstanceOf(PropertyChange.Held.class,
                PropertyChange.of(arguments, CONTRACT), "the change was refused").change();
    }

    private static PropertyChange.Refused refusal(DocumentValue.Mapping arguments) {
        return assertInstanceOf(PropertyChange.Refused.class,
                PropertyChange.of(arguments, CONTRACT), "the change was accepted");
    }

    private static ComponentPlacement placement(DocumentValue written) {
        return assertInstanceOf(ComponentPlacement.Held.class,
                ComponentPlacement.of(written, CONTRACT), "the placement was refused").placement();
    }

    private static ComponentPlacement.Refused placementRefusal(DocumentValue written) {
        return assertInstanceOf(ComponentPlacement.Refused.class,
                ComponentPlacement.of(written, CONTRACT), "the placement was accepted");
    }

    private static DocumentValue.Mapping placementBefore(String sibling) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(ComponentPlacement.MODE,
                new DocumentValue.Text(ComponentPlacement.BEFORE_MODE));
        written.put(ComponentPlacement.SIBLING_NAME, new DocumentValue.Text(sibling));
        return new DocumentValue.Mapping(written);
    }

    private static DocumentValue.Mapping placementLast() {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(ComponentPlacement.MODE, new DocumentValue.Text(ComponentPlacement.LAST_MODE));
        return new DocumentValue.Mapping(written);
    }

    private static DocumentValue.Mapping change(SequencedMap<String, DocumentValue> properties,
                                                List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (!properties.isEmpty()) {
            members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(properties));
        }
        if (!removed.isEmpty()) {
            members.put(PropertyChange.REMOVED_PROPERTY_NAMES,
                    new DocumentValue.Sequence(removed.stream()
                            .map(name -> (DocumentValue) new DocumentValue.Text(name))
                            .toList()));
        }
        return new DocumentValue.Mapping(members);
    }

    private static SequencedMap<String, DocumentValue> single(String name, String value) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        held.put(PropertyValue.VALUE, scalar(value));
        written.put(name, new DocumentValue.Mapping(held));
        return written;
    }

    private static SequencedMap<String, DocumentValue> multiple(String name, String... values) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.MULTIPLE));
        held.put(PropertyValue.VALUES, new DocumentValue.Sequence(
                java.util.Arrays.stream(values)
                        .map(MutationVocabularyTest::scalar)
                        .toList()));
        written.put(name, new DocumentValue.Mapping(held));
        return written;
    }

    private static SequencedMap<String, DocumentValue> many(int count) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        java.util.stream.IntStream.range(0, count)
                .forEach(index -> written.putAll(single("property-" + index, "a value")));
        return written;
    }

    private static List<String> names(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "property-" + index)
                .toList();
    }

    private static DocumentValue scalar(String value) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        written.put(PropertyScalar.VALUE, new DocumentValue.Text(value));
        return new DocumentValue.Mapping(written);
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
