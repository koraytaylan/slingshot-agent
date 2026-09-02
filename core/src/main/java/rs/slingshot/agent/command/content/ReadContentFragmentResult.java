// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One fragment's elements in one variation, keyed by their own names.
 *
 * <p>An element is text, or a list of text where it holds several, and its type is not restated
 * beside it. The model is what declares an element's type and the answer names the model, so a
 * caller who needs the type reads it from the one place that defines it rather than from a copy
 * that can disagree.</p>
 *
 * <p>The answer echoes the fragment's own address and the variation it read. A caller comparing
 * those against what they asked for catches a whole class of defect that the elements alone
 * cannot show — most obviously a fragment that answered from its master when a variation was
 * meant.</p>
 */
public final class ReadContentFragmentResult {

    private ReadContentFragmentResult() {
    }

    /** The member the model's address is carried in. */
    public static final String MODEL_PATH = "model_path";

    /** The member the fragment's own address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the variation that was read is carried in. */
    public static final String VARIATION_NAME = "variation_name";

    /** The member the fragment's title is carried in, where it has one. */
    public static final String TITLE = "title";

    /** The member the elements are carried in, keyed by their own names. */
    public static final String ELEMENTS = "elements";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS =
            List.of(ELEMENTS, MODEL_PATH, REPOSITORY_PATH, TITLE, VARIATION_NAME);

    /**
     * One element of one fragment as a caller receives it.
     *
     * <p>Its value is text, or a list of text where the element holds several. That is what the
     * client's schema declares and it is the whole of what a fragment element is on the wire: an
     * element's type belongs to the model, which the answer names, rather than being restated
     * beside every value.</p>
     *
     * @param name the element's own name, which is how the model declares it
     * @param values the value, or the values where the element holds several
     */
    public record Element(String name, List<String> values) {

        /** Holds the values apart from whatever produced them. */
        public Element {
            values = List.copyOf(values);
        }

        /**
         * This element's values.
         *
         * @return the values, which nothing may add to
         */
        @Override
        public List<String> values() {
            return Collections.unmodifiableList(values);
        }

        /**
         * Whether this element holds one value rather than a list of them.
         *
         * @return whether it does, which decides how the value is written
         */
        public boolean isSingle() {
            return values.size() == 1;
        }
    }

    /**
     * The result one fragment read produces.
     *
     * @param repositoryPath the fragment's own address, echoed so an answer says what it is of
     * @param modelPath the address of the model this fragment is an instance of
     * @param variationName the variation that was read
     * @param title what the fragment is called, which is empty where it is called nothing
     * @param elements the elements
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath, String modelPath,
                                                   String variationName, String title,
                                                   List<Element> elements) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        result.put(MODEL_PATH, new DocumentValue.Text(modelPath));
        result.put(VARIATION_NAME, new DocumentValue.Text(variationName));
        if (!title.isEmpty()) {
            result.put(TITLE, new DocumentValue.Text(title));
        }
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        elements.forEach(element -> held.put(element.name(), valueOf(element)));
        result.put(ELEMENTS, new DocumentValue.Mapping(held));
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue valueOf(Element element) {
        return element.isSingle() ? new DocumentValue.Text(element.values().getFirst())
                : new DocumentValue.Sequence(element.values().stream()
                        .map(value -> (DocumentValue) new DocumentValue.Text(value))
                        .toList());
    }
}
