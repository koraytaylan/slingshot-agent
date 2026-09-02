// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What a content fragment's elements are being set to.
 *
 * <p>Text, or a list of text, keyed by the element's own name. That is the whole vocabulary: an
 * element's type belongs to the model that declares it, and this document says what the values are
 * rather than restating what they are supposed to be. A copy of the type here would be a second
 * answer to a question the model already answers.</p>
 *
 * <p>Which elements a fragment may have is not decided here either. This reads what was asked for;
 * whether the model declares those elements is a question about a particular fragment, and it is
 * asked when there is a fragment to ask it about.</p>
 *
 * @param values the elements, by name, in the order they were written
 */
public record FragmentElements(SequencedMap<String, List<String>> values) {

    /** The member a caller carries these in. */
    public static final String ARGUMENT_MEMBER = "elements";

    /** An argument naming no elements, which changes none of them. */
    public static final FragmentElements NOTHING = new FragmentElements(new LinkedHashMap<>());

    /** Holds the elements apart from whatever the caller still has a reference to. */
    public FragmentElements {
        final SequencedMap<String, List<String>> held = new LinkedHashMap<>();
        values.forEach((name, value) -> held.put(name, List.copyOf(value)));
        values = held;
    }

    /**
     * The elements being set.
     *
     * @return them by name, which nothing may add to
     */
    @Override
    public SequencedMap<String, List<String>> values() {
        return Collections.unmodifiableSequencedMap(values);
    }

    /**
     * Whether this would leave every element as it was.
     *
     * @return whether it would
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Why one element document is not one this contract writes. */
    public enum Refusal {
        /** The elements are not a document of them. */
        NOT_A_DOCUMENT,
        /** More elements were named than the contract allows. */
        TOO_MANY_ELEMENTS,
        /** An element's name is longer than the contract allows. */
        NAME_TOO_LONG,
        /** An element's value is neither text nor a list of it. */
        VALUE_REJECTED,
        /** An element's list holds more values than the contract allows. */
        TOO_MANY_VALUES,
        /** An element's value is longer than the contract allows. */
        VALUE_TOO_LONG
    }

    /** The result of reading one: the elements, or the one reason there are none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * Elements this contract writes.
     *
     * @param elements what was asked
     */
    public record Held(FragmentElements elements) implements Outcome {
    }

    /**
     * Ones it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the element rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads the elements one argument names, which is none where it names none.
     *
     * @param arguments the whole argument document
     * @param contract the authenticated contract, which bounds the count, the names and the values
     * @return the elements, or the one reason there are none
     */
    public static Outcome of(DocumentValue.Mapping arguments, AgentContract contract) {
        final Optional<DocumentValue> asked = arguments.member(ARGUMENT_MEMBER);
        if (asked.isEmpty()) {
            return new Held(NOTHING);
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Mapping elements)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    ARGUMENT_MEMBER + " names elements by name, each with what to set it to");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_ELEMENTS);
        if (elements.members().size() > bound) {
            return new Refused(Refusal.TOO_MANY_ELEMENTS, elements.members().size()
                    + " elements is more than the " + bound + " one fragment holds");
        }
        final SequencedMap<String, List<String>> held = new LinkedHashMap<>();
        for (final var element : elements.members().entrySet()) {
            final Outcome named = named(element.getKey(), contract);
            if (named instanceof Refused) {
                return named;
            }
            final Outcome valued = valued(element.getKey(), element.getValue(), contract, held);
            if (valued instanceof Refused) {
                return valued;
            }
        }
        return new Held(new FragmentElements(held));
    }

    private static Outcome named(String name, AgentContract contract) {
        final long bound =
                contract.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_ELEMENT_NAME_BYTES);
        return name.length() > bound
                ? new Refused(Refusal.NAME_TOO_LONG, name.length() + " characters is longer than"
                        + " the " + bound + " an element's name may be")
                : new Held(NOTHING);
    }

    private static Outcome valued(String name, DocumentValue value, AgentContract contract,
                                  SequencedMap<String, List<String>> into) {
        if (value instanceof final DocumentValue.Text text) {
            return sized(name, List.of(text.value()), contract, into);
        }
        if (!(value instanceof final DocumentValue.Sequence items)) {
            return new Refused(Refusal.VALUE_REJECTED,
                    name + " is set to text or to a list of text, and to nothing else");
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_CONTENT_FRAGMENT_ELEMENT_VALUES);
        if (items.items().size() > bound) {
            return new Refused(Refusal.TOO_MANY_VALUES, name + " holds " + items.items().size()
                    + " values, more than the " + bound + " one element holds");
        }
        final List<String> values = items.items().stream()
                .filter(item -> item instanceof DocumentValue.Text)
                .map(item -> ((DocumentValue.Text) item).value())
                .toList();
        return values.size() != items.items().size()
                ? new Refused(Refusal.VALUE_REJECTED, name + " holds something that is not text")
                : sized(name, values, contract, into);
    }

    private static Outcome sized(String name, List<String> values, AgentContract contract,
                                 SequencedMap<String, List<String>> into) {
        final long bound = contract.value(ContractLimit.MAXIMUM_PROPERTY_STRING_BYTES);
        final Optional<String> over = values.stream()
                .filter(value -> value.length() > bound)
                .findFirst();
        if (over.isPresent()) {
            return new Refused(Refusal.VALUE_TOO_LONG, name + " holds a value longer than the "
                    + bound + " one value may be");
        }
        into.put(name, values);
        return new Held(NOTHING);
    }
}
