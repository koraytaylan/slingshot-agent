// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The predicate language two searches share, proved as a language rather than through either.
 *
 * <p>It is the one piece of this surface a caller composes freely, so what it refuses matters more
 * than what it accepts: every refusal here is a question that would otherwise be answered with a
 * silently empty result, which is the failure mode that costs somebody a day of looking.</p>
 */
final class PropertyPredicateTest {

    private static final AgentContract CONTRACT = contract();

    private static final String TITLE = "jcr:content/jcr:title";

    @Test
    @DisplayName("each operator takes exactly what it declares, and nothing else")
    void eachoperatorTakesWhatItDeclares() {
        assertInstanceOf(PropertyPredicate.Presence.class,
                held(predicate(PredicateOperator.EXISTS, TITLE)),
                "an operator asking only whether a property is there was not read as one");
        assertEquals(PropertyPredicate.Refusal.FIELDS_DO_NOT_MATCH_OPERATOR,
                refused(with(predicate(PredicateOperator.EXISTS, TITLE), PropertyPredicate.VALUE,
                        scalar(ScalarKind.STRING, "A title"))).refusal(),
                "an exists carrying a value was accepted, and a caller who sent one meant"
                        + " something this operator does not do");
        assertEquals(PropertyPredicate.Refusal.FIELDS_DO_NOT_MATCH_OPERATOR,
                refused(predicate(PredicateOperator.EQUALS, TITLE)).refusal(),
                "a comparison with nothing to compare against was accepted");
        assertEquals(PropertyPredicate.Refusal.FIELDS_DO_NOT_MATCH_OPERATOR,
                refused(with(predicate(PredicateOperator.SCALAR_IN, TITLE),
                        PropertyPredicate.VALUE, scalar(ScalarKind.STRING, "A"))).refusal(),
                "a membership given one value rather than a list was accepted");
    }

    @Test
    @DisplayName("an operator this language does not have is refused, naming the ten it does")
    void anunknownOperatorNamesTheTen() {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(PropertyPredicate.OPERATOR, new DocumentValue.Text("matches_regular_expression"));
        written.put(PropertyPredicate.PROPERTY_PATH, new DocumentValue.Text(TITLE));
        final PropertyPredicate.Refused refused = refused(new DocumentValue.Mapping(written));
        assertEquals(PropertyPredicate.Refusal.UNKNOWN_OPERATOR, refused.refusal());
        assertTrue(PredicateOperator.spellings().stream()
                        .allMatch(spelling -> refused.detail().contains(spelling)),
                "the refusal does not tell the caller what can be asked instead: "
                        + refused.detail());
    }

    @Test
    @DisplayName("a property is named relatively, because a search resolves it from each candidate")
    void apropertyIsNamedRelatively() {
        assertEquals(PropertyPredicate.Refusal.PROPERTY_PATH_REJECTED,
                refused(predicate(PredicateOperator.EXISTS, "/content/site/jcr:title")).refusal(),
                "an absolute path was accepted, and a predicate resolved from the root rather than"
                        + " from the candidate asks about one node however many were examined");
        assertEquals(PropertyPredicate.Refusal.PROPERTY_PATH_REJECTED,
                refused(predicate(PredicateOperator.EXISTS, "")).refusal());
    }

    @Test
    @DisplayName("a membership names at least one value, each once, all of one kind")
    void amembershipIsWellFormed() {
        assertEquals(PropertyPredicate.Refusal.VALUES_EMPTY,
                refused(membership(PredicateOperator.SCALAR_IN)).refusal(),
                "a membership looking for nothing was accepted");
        assertEquals(PropertyPredicate.Refusal.VALUES_NOT_UNIQUE,
                refused(membership(PredicateOperator.SCALAR_IN, scalar(ScalarKind.STRING, "A"),
                        scalar(ScalarKind.STRING, "A"))).refusal(),
                "a membership naming one value twice was accepted, which asks the same question"
                        + " twice and answers it once");
        assertEquals(PropertyPredicate.Refusal.VALUES_NOT_HOMOGENEOUS,
                refused(membership(PredicateOperator.SCALAR_IN, scalar(ScalarKind.STRING, "1"),
                        scalar(ScalarKind.INTEGER, "1"))).refusal(),
                "a membership mixing kinds was accepted, and unlike kinds never compare");
    }

    @Test
    @DisplayName("an ordered comparison against something with no order is refused")
    void anorderedComparisonNeedsAnOrder() {
        final PropertyPredicate.Refused refused = refused(with(
                predicate(PredicateOperator.LESS_THAN, TITLE), PropertyPredicate.VALUE,
                scalar(ScalarKind.REPOSITORY_PATH, "/content/site")));
        assertEquals(PropertyPredicate.Refusal.VALUE_NOT_ORDERED, refused.refusal(),
                "asking whether one address sorts before another was accepted, and that is a"
                        + " question with no answer rather than one whose answer is no");
        assertTrue(refused.detail().contains(ScalarKind.REPOSITORY_PATH.spelling()),
                refused.detail());
        assertInstanceOf(PropertyPredicate.Comparison.class,
                held(with(predicate(PredicateOperator.LESS_THAN, TITLE), PropertyPredicate.VALUE,
                        scalar(ScalarKind.INTEGER, "10"))),
                "an ordered comparison against a number was refused");
    }

    @Test
    @DisplayName("what a predicate answers about stored values is what its operator says")
    void everyoperatorAnswersWhatItSays() {
        assertTrue(held(predicate(PredicateOperator.EXISTS, TITLE)).isSatisfiedBy(List.of("A")),
                "a property that is there was reported absent");
        assertTrue(!held(predicate(PredicateOperator.EXISTS, TITLE)).isSatisfiedBy(List.of()),
                "a property that is not there was reported present");
        assertTrue(comparison(PredicateOperator.EQUALS, ScalarKind.STRING, "A")
                        .isSatisfiedBy(List.of("A")),
                "a property holding the value did not satisfy equals");
        assertTrue(!comparison(PredicateOperator.EQUALS, ScalarKind.STRING, "A")
                        .isSatisfiedBy(List.of("B")),
                "a property holding something else satisfied equals");
        assertTrue(comparison(PredicateOperator.NOT_EQUALS, ScalarKind.STRING, "A")
                        .isSatisfiedBy(List.of("B")),
                "a property holding something else did not satisfy not-equals");
        assertTrue(!comparison(PredicateOperator.NOT_EQUALS, ScalarKind.STRING, "A")
                        .isSatisfiedBy(List.of("A", "B")),
                "a property holding the value among others satisfied not-equals, so a multi-valued"
                        + " property could be both equal and not equal to the same value");
    }

    @Test
    @DisplayName("numbers are compared as numbers rather than as the text they were written as")
    void numbersAreComparedAsNumbers() {
        assertTrue(comparison(PredicateOperator.GREATER_THAN, ScalarKind.INTEGER, "9")
                        .isSatisfiedBy(List.of("10")),
                "ten did not sort after nine, so a whole number was compared as text");
        assertTrue(comparison(PredicateOperator.LESS_THAN_OR_EQUAL, ScalarKind.INTEGER, "10")
                        .isSatisfiedBy(List.of("10")),
                "a value equal to the bound did not satisfy less-than-or-equal");
        assertTrue(comparison(PredicateOperator.GREATER_THAN_OR_EQUAL, ScalarKind.DECIMAL, "1.50")
                        .isSatisfiedBy(List.of("1.5")),
                "a decimal written with a different scale did not compare equal");
        assertTrue(!comparison(PredicateOperator.LESS_THAN, ScalarKind.INTEGER, "not-a-number")
                        .isSatisfiedBy(List.of("1")),
                "a value that is not a number was compared as one rather than answering no");
    }

    @Test
    @DisplayName("membership asks about any or all of its values, and says which")
    void membershipAsksAnyOrAll() {
        final PropertyPredicate any = held(membership(PredicateOperator.LIST_CONTAINS_ANY,
                scalar(ScalarKind.STRING, "A"), scalar(ScalarKind.STRING, "B")));
        assertTrue(any.isSatisfiedBy(List.of("A")), "a list holding one named value did not match"
                + " a predicate asking for any of them");
        final PropertyPredicate all = held(membership(PredicateOperator.LIST_CONTAINS_ALL,
                scalar(ScalarKind.STRING, "A"), scalar(ScalarKind.STRING, "B")));
        assertTrue(!all.isSatisfiedBy(List.of("A")),
                "a list holding one of two named values matched a predicate asking for both");
        assertTrue(all.isSatisfiedBy(List.of("A", "B", "C")),
                "a list holding both named values and one more did not match");
        assertEquals(TITLE, all.propertyPath());
        assertEquals(2, ((PropertyPredicate.Membership) all).values().size());
    }

    @Test
    @DisplayName("both bounds are proved at their value and one past it")
    void bothboundsAreProved() {
        final long values = CONTRACT.value(ContractLimit.MAXIMUM_PROPERTY_PREDICATE_VALUES);
        assertInstanceOf(PropertyPredicate.Membership.class,
                held(membership(PredicateOperator.SCALAR_IN, distinct((int) values))),
                "a membership at the bound was refused");
        assertEquals(PropertyPredicate.Refusal.VALUES_TOO_MANY,
                refused(membership(PredicateOperator.SCALAR_IN, distinct((int) values + 1)))
                        .refusal());
        final long predicates = CONTRACT.value(ContractLimit.MAXIMUM_PROPERTY_PREDICATES);
        assertEquals(predicates, listOf((int) predicates).predicates().size(),
                "a list of predicates at the bound was not read whole");
        assertEquals(PropertyPredicate.Refusal.TOO_MANY_PREDICATES,
                assertInstanceOf(PropertyPredicate.Refused.class,
                        PropertyPredicate.listOf(list((int) predicates + 1), CONTRACT),
                        "a list past the bound was accepted").refusal());
    }

    @Test
    @DisplayName("an argument naming no predicates asks about nothing further")
    void nopredicatesIsNoNarrowing() {
        assertEquals(List.of(), assertInstanceOf(PropertyPredicate.Held.class,
                        PropertyPredicate.listOf(new DocumentValue.Mapping(new LinkedHashMap<>()),
                                CONTRACT),
                        "an argument naming no predicates was refused").predicates());
        final SequencedMap<String, DocumentValue> notAList = new LinkedHashMap<>();
        notAList.put(PropertyPredicate.ARGUMENT_MEMBER, new DocumentValue.Text("everything"));
        assertEquals(PropertyPredicate.Refusal.NOT_A_LIST,
                assertInstanceOf(PropertyPredicate.Refused.class,
                        PropertyPredicate.listOf(new DocumentValue.Mapping(notAList), CONTRACT),
                        "something that is not a list of predicates was accepted").refusal());
    }

    @Test
    @DisplayName("a value carries its own kind, and one this contract does not compare is refused")
    void avalueCarriesItsOwnKind() {
        assertEquals(PropertyScalar.Refusal.NOT_A_DOCUMENT,
                scalarRefusal(new DocumentValue.Text("A")).refusal(),
                "a bare string was read as a value, so its kind would have been inferred");
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(PropertyScalar.TYPE, new DocumentValue.Text("colour"));
        unknown.put(PropertyScalar.VALUE, new DocumentValue.Text("blue"));
        assertEquals(PropertyScalar.Refusal.UNKNOWN_KIND,
                scalarRefusal(new DocumentValue.Mapping(unknown)).refusal());
        final SequencedMap<String, DocumentValue> untyped = new LinkedHashMap<>();
        untyped.put(PropertyScalar.VALUE, new DocumentValue.Text("blue"));
        assertEquals(PropertyScalar.Refusal.UNKNOWN_KIND,
                scalarRefusal(new DocumentValue.Mapping(untyped)).refusal());
        final SequencedMap<String, DocumentValue> nested = new LinkedHashMap<>();
        nested.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        nested.put(PropertyScalar.VALUE, new DocumentValue.Whole(1));
        assertEquals(PropertyScalar.Refusal.VALUE_NOT_A_SCALAR,
                scalarRefusal(new DocumentValue.Mapping(nested)).refusal(),
                "a value written as a number was read as one, and this contract writes every"
                        + " value as text so that its kind is the only thing that types it");
        final SequencedMap<String, DocumentValue> flagged = new LinkedHashMap<>();
        flagged.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.BOOLEAN.spelling()));
        flagged.put(PropertyScalar.VALUE, new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        assertEquals("true", assertInstanceOf(PropertyScalar.Held.class,
                        PropertyScalar.of(new DocumentValue.Mapping(flagged)),
                        "a flag was refused as a value").scalar().value());
    }

    @Test
    @DisplayName("every kind and every operator names itself back from its own spelling")
    void everyspellingNamesItsOwnValueBack() {
        for (final ScalarKind kind : ScalarKind.values()) {
            assertEquals(kind, ScalarKind.named(kind.spelling()).orElseThrow());
        }
        assertEquals(java.util.Optional.empty(), ScalarKind.named("colour"));
        for (final PredicateOperator operator : PredicateOperator.values()) {
            assertEquals(operator, PredicateOperator.named(operator.spelling()).orElseThrow());
        }
        assertEquals(java.util.Optional.empty(), PredicateOperator.named("sounds_like"));
        assertEquals(ScalarKind.Ordering.UNORDERED, ScalarKind.REPOSITORY_PATH.ordering());
        assertEquals(PredicateOperator.Comparand.NOTHING, PredicateOperator.EXISTS.comparand());
    }

    private static PropertyPredicate comparison(PredicateOperator operator, ScalarKind kind,
                                                String value) {
        return held(with(predicate(operator, TITLE), PropertyPredicate.VALUE,
                scalar(kind, value)));
    }

    private static PropertyPredicate held(DocumentValue written) {
        return assertInstanceOf(PropertyPredicate.Held.class,
                PropertyPredicate.of(written, CONTRACT), "the predicate was refused")
                .predicates().getFirst();
    }

    private static PropertyPredicate.Refused refused(DocumentValue written) {
        return assertInstanceOf(PropertyPredicate.Refused.class,
                PropertyPredicate.of(written, CONTRACT), "the predicate was accepted");
    }

    private static PropertyScalar.Refused scalarRefusal(DocumentValue written) {
        return assertInstanceOf(PropertyScalar.Refused.class, PropertyScalar.of(written),
                "the value was accepted");
    }

    private static PropertyPredicate.Held listOf(int count) {
        return assertInstanceOf(PropertyPredicate.Held.class,
                PropertyPredicate.listOf(list(count), CONTRACT), "the list was refused");
    }

    private static DocumentValue.Mapping list(int count) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PropertyPredicate.ARGUMENT_MEMBER, new DocumentValue.Sequence(
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(index -> (DocumentValue) predicate(PredicateOperator.EXISTS,
                                "property-" + index))
                        .toList()));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue[] distinct(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> scalar(ScalarKind.STRING, "value-" + index))
                .toArray(DocumentValue[]::new);
    }

    private static DocumentValue.Mapping predicate(PredicateOperator operator, String property) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(PropertyPredicate.OPERATOR, new DocumentValue.Text(operator.spelling()));
        written.put(PropertyPredicate.PROPERTY_PATH, new DocumentValue.Text(property));
        return new DocumentValue.Mapping(written);
    }

    private static DocumentValue.Mapping membership(PredicateOperator operator,
                                                    DocumentValue... values) {
        return with(predicate(operator, TITLE), PropertyPredicate.VALUES,
                new DocumentValue.Sequence(List.of(values)));
    }

    private static DocumentValue.Mapping with(DocumentValue.Mapping written, String member,
                                              DocumentValue value) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>(written.members());
        held.put(member, value);
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue.Mapping scalar(ScalarKind kind, String value) {
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put(PropertyScalar.TYPE, new DocumentValue.Text(kind.spelling()));
        written.put(PropertyScalar.VALUE, new DocumentValue.Text(value));
        return new DocumentValue.Mapping(written);
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
