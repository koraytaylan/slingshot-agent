// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What a search asks about one property, said structurally.
 *
 * <p>No command takes query text. A caller cannot send anything a repository would interpret,
 * because a string that reaches a query engine is a string that can address content the command was
 * never pointed at. A predicate is a closed value instead: an operator, a property to look at, and
 * — for the operators that need one — a typed value to compare against.</p>
 *
 * <p>Three shapes rather than one shape with optional members, because the operator decides which
 * members exist and a single record would have to hold an absent value for two of the three. A
 * predicate that carries what its operator does not take is refused when it is read.</p>
 *
 * <p>Resolution is exact. A property path names child resources and then one property, resolved
 * from the candidate node with no descendant search and no fallback: a predicate that finds nothing
 * has found nothing.</p>
 */
public sealed interface PropertyPredicate
        permits PropertyPredicate.Presence, PropertyPredicate.Comparison,
                PropertyPredicate.Membership {

    /** The member a list of these is carried in. */
    String ARGUMENT_MEMBER = "property_predicates";

    /** The member one predicate's operator is carried in. */
    String OPERATOR = "operator";

    /** The member the property to look at is carried in. */
    String PROPERTY_PATH = "property_path";

    /** The member a comparison's value is carried in. */
    String VALUE = "value";

    /** The member a membership's values are carried in. */
    String VALUES = "values";

    /**
     * Every member a predicate's own document has, the value's included.
     *
     * <p>Borrowed by the searches that take predicates rather than restated by each, for the same
     * reason a window's members are: a schema declares the nested document's members beside the
     * outer ones, and two copies of one list drift.</p>
     */
    List<String> MEMBERS = List.of(OPERATOR, PROPERTY_PATH, PropertyScalar.TYPE, VALUE, VALUES);

    /**
     * Every member a value inside a predicate has, which the predicate's own list already carries.
     *
     * <p>Named here rather than left implicit because a value is a document of its own: it has a
     * kind and a value, and a schema declares both beside the predicate that holds them.</p>
     */
    List<String> VALUE_MEMBERS = List.of(PropertyScalar.TYPE, PropertyScalar.VALUE);

    /**
     * Which property this predicate is about.
     *
     * @return the property path, relative to the candidate node
     */
    String propertyPath();

    /**
     * Whether one candidate's stored values satisfy this predicate.
     *
     * @param stored what the repository holds under this property, empty where it holds nothing
     * @return whether the candidate matches
     */
    boolean isSatisfiedBy(List<String> stored);

    /**
     * A predicate asking only whether a property is there.
     *
     * @param propertyPath which property
     */
    record Presence(String propertyPath) implements PropertyPredicate {

        @Override
        public boolean isSatisfiedBy(List<String> stored) {
            // A repository can hold a property with no values, and it is still there. Presence is
            // about the property rather than about what is in it.
            return !stored.isEmpty();
        }
    }

    /**
     * A predicate comparing a property against one value.
     *
     * @param operator which comparison
     * @param propertyPath which property
     * @param value what to compare against
     */
    record Comparison(PredicateOperator operator, String propertyPath, PropertyScalar value)
            implements PropertyPredicate {

        @Override
        public boolean isSatisfiedBy(List<String> stored) {
            if (operator == PredicateOperator.NOT_EQUALS) {
                return stored.stream().noneMatch(held -> value.compareWith(held) == 0);
            }
            return stored.stream().anyMatch(this::holds);
        }

        private boolean holds(String held) {
            final int order = value.compareWith(held);
            // The order is the stored value's against the asked-about one, so "less than" is the
            // stored value sorting first. Reading it the other way round makes every one of these
            // four answer the opposite question, which no single example would reveal.
            return switch (operator) {
                case EQUALS -> order == 0;
                case LESS_THAN -> order < 0;
                case LESS_THAN_OR_EQUAL -> order <= 0;
                case GREATER_THAN -> order > 0;
                case GREATER_THAN_OR_EQUAL -> order >= 0;
                default -> false;
            };
        }
    }

    /**
     * A predicate asking about membership among several values.
     *
     * @param operator which membership
     * @param propertyPath which property
     * @param values what to look for, all of one kind and each named once
     */
    record Membership(PredicateOperator operator, String propertyPath, List<PropertyScalar> values)
            implements PropertyPredicate {

        /** Holds the values apart from whatever produced them. */
        public Membership {
            values = List.copyOf(values);
        }

        /**
         * What this predicate looks for.
         *
         * @return the values, which nothing may add to
         */
        @Override
        public List<PropertyScalar> values() {
            return Collections.unmodifiableList(values);
        }

        @Override
        public boolean isSatisfiedBy(List<String> stored) {
            return operator == PredicateOperator.LIST_CONTAINS_ALL
                    ? values.stream().allMatch(value -> matched(value, stored))
                    : values.stream().anyMatch(value -> matched(value, stored));
        }

        private static boolean matched(PropertyScalar value, List<String> stored) {
            return stored.stream().anyMatch(held -> value.compareWith(held) == 0);
        }
    }

    /** Why a predicate is not one this language defines. */
    enum Refusal {
        /** The predicate is not an object. */
        NOT_A_DOCUMENT,
        /** The operator is not one of the ten. */
        UNKNOWN_OPERATOR,
        /** The property path is absent, empty, or begins where a relative path does not. */
        PROPERTY_PATH_REJECTED,
        /** The predicate carries a member its operator does not take, or omits one it needs. */
        FIELDS_DO_NOT_MATCH_OPERATOR,
        /** A value is not one this vocabulary holds. */
        VALUE_REJECTED,
        /** A membership predicate names no values. */
        VALUES_EMPTY,
        /** A membership predicate names one value twice. */
        VALUES_NOT_UNIQUE,
        /** A membership predicate mixes kinds. */
        VALUES_NOT_HOMOGENEOUS,
        /** A membership predicate names more values than the contract allows. */
        VALUES_TOO_MANY,
        /** An ordered comparison was given a value that has no order. */
        VALUE_NOT_ORDERED,
        /** A search composes more predicates than the contract allows. */
        TOO_MANY_PREDICATES,
        /** The list of predicates is not a list. */
        NOT_A_LIST
    }

    /** The result of reading one, or a list of them: the predicates, or the one reason there are none. */
    sealed interface Outcome permits Held, Refused {
    }

    /**
     * Predicates this language defines.
     *
     * @param predicates the predicates, in the order they were written
     */
    record Held(List<PropertyPredicate> predicates) implements Outcome {

        /** Holds the predicates apart from whatever produced them. */
        public Held {
            predicates = List.copyOf(predicates);
        }

        /**
         * What was read, in the order it was written.
         *
         * @return them, which nothing may add to
         */
        @Override
        public List<PropertyPredicate> predicates() {
            return Collections.unmodifiableList(predicates);
        }
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads the list of predicates one argument names, which is empty where it names none.
     *
     * @param arguments the whole argument document
     * @param contract the authenticated contract, which bounds both the list and each membership
     * @return the predicates, or the one reason there are none
     */
    static Outcome listOf(DocumentValue.Mapping arguments, AgentContract contract) {
        final Optional<DocumentValue> asked = arguments.member(ARGUMENT_MEMBER);
        if (asked.isEmpty()) {
            return new Held(List.of());
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence written)) {
            return new Refused(Refusal.NOT_A_LIST, ARGUMENT_MEMBER + " is a list of predicates");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PROPERTY_PREDICATES);
        if (written.items().size() > bound) {
            return new Refused(Refusal.TOO_MANY_PREDICATES, written.items().size()
                    + " predicates is more than the " + bound + " one search composes");
        }
        final List<PropertyPredicate> predicates = new ArrayList<>();
        for (final DocumentValue item : written.items()) {
            final Outcome read = of(item, contract);
            if (read instanceof Refused) {
                return read;
            }
            predicates.addAll(((Held) read).predicates());
        }
        return new Held(Collections.unmodifiableList(predicates));
    }

    /**
     * Reads one written predicate.
     *
     * @param written the predicate as the caller wrote it
     * @param contract the authenticated contract, which bounds a membership's values
     * @return the predicate, or the one reason there is none
     */
    static Outcome of(DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "a predicate is an object with an operator and a property in it");
        }
        if (!(mapping.member(OPERATOR).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text spelled)) {
            return new Refused(Refusal.UNKNOWN_OPERATOR, OPERATOR + " names what is being asked");
        }
        final Optional<PredicateOperator> operator = PredicateOperator.named(spelled.value());
        if (operator.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_OPERATOR, spelled.value() + " is not one of the"
                    + " operators this language defines, which are "
                    + PredicateOperator.spellings());
        }
        if (!(mapping.member(PROPERTY_PATH).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text path)
                || path.value().isEmpty() || path.value().charAt(0) == '/') {
            return new Refused(Refusal.PROPERTY_PATH_REJECTED, PROPERTY_PATH + " names a property"
                    + " relative to the node being examined, so it does not begin at the root");
        }
        return shaped(operator.orElseThrow(), path.value(), mapping, contract);
    }

    private static Outcome shaped(PredicateOperator operator, String path,
                                  DocumentValue.Mapping mapping, AgentContract contract) {
        final boolean carriesValue = mapping.member(VALUE).isPresent();
        final boolean carriesValues = mapping.member(VALUES).isPresent();
        return switch (operator.comparand()) {
            case NOTHING -> carriesValue || carriesValues
                    ? new Refused(Refusal.FIELDS_DO_NOT_MATCH_OPERATOR, operator.spelling()
                            + " asks only whether a property is there, so it takes no value")
                    : new Held(List.of(new Presence(path)));
            case ONE_VALUE, ONE_ORDERED_VALUE -> carriesValues || !carriesValue
                    ? new Refused(Refusal.FIELDS_DO_NOT_MATCH_OPERATOR, operator.spelling()
                            + " compares against exactly one value")
                    : compared(operator, path, mapping.member(VALUE).orElseThrow());
            case SEVERAL_VALUES -> carriesValue || !carriesValues
                    ? new Refused(Refusal.FIELDS_DO_NOT_MATCH_OPERATOR, operator.spelling()
                            + " looks for several values")
                    : membership(operator, path, mapping.member(VALUES).orElseThrow(), contract);
        };
    }

    private static Outcome compared(PredicateOperator operator, String path, DocumentValue written) {
        final PropertyScalar.Outcome read = PropertyScalar.of(written);
        if (read instanceof final PropertyScalar.Refused refused) {
            return new Refused(Refusal.VALUE_REJECTED, refused.detail());
        }
        final PropertyScalar value = ((PropertyScalar.Held) read).scalar();
        if (operator.comparand() == PredicateOperator.Comparand.ONE_ORDERED_VALUE
                && value.kind().ordering() == ScalarKind.Ordering.UNORDERED) {
            return new Refused(Refusal.VALUE_NOT_ORDERED, operator.spelling() + " asks which of two"
                    + " values sorts first, and a " + value.kind().spelling() + " has no order. A"
                    + " comparison that cannot be answered is refused rather than answered"
                    + " arbitrarily.");
        }
        return new Held(List.of(new Comparison(operator, path, value)));
    }

    private static Outcome membership(PredicateOperator operator, String path,
                                      DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Sequence items)) {
            return new Refused(Refusal.VALUE_REJECTED, VALUES + " is a list of typed values");
        }
        if (items.items().isEmpty()) {
            return new Refused(Refusal.VALUES_EMPTY,
                    operator.spelling() + " looks for at least one value");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PROPERTY_PREDICATE_VALUES);
        if (items.items().size() > bound) {
            return new Refused(Refusal.VALUES_TOO_MANY, items.items().size() + " values is more"
                    + " than the " + bound + " one predicate names");
        }
        final List<PropertyScalar> values = new ArrayList<>();
        for (final DocumentValue item : items.items()) {
            final PropertyScalar.Outcome read = PropertyScalar.of(item);
            if (read instanceof final PropertyScalar.Refused refused) {
                return new Refused(Refusal.VALUE_REJECTED, refused.detail());
            }
            values.add(((PropertyScalar.Held) read).scalar());
        }
        return uniform(operator, path, values);
    }

    private static Outcome uniform(PredicateOperator operator, String path,
                                   List<PropertyScalar> values) {
        final Set<PropertyScalar> distinct = new LinkedHashSet<>(values);
        if (distinct.size() != values.size()) {
            return new Refused(Refusal.VALUES_NOT_UNIQUE, operator.spelling() + " names one value"
                    + " twice, which asks the same question twice and answers it once");
        }
        if (values.stream().map(PropertyScalar::kind).distinct().count() > 1) {
            return new Refused(Refusal.VALUES_NOT_HOMOGENEOUS, operator.spelling() + " looks for"
                    + " values of one kind; values of unlike kinds never compare, so a mixed list"
                    + " asks a question with no answer");
        }
        return new Held(List.of(new Membership(operator, path, values)));
    }
}
