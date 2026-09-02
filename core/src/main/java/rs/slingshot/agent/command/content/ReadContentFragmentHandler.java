// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One fragment read in one variation, typed by its model.
 *
 * <p>A fragment whose model cannot be resolved is refused rather than reported untyped. The
 * alternative is worse than it looks: a caller receiving elements with no declared types would read
 * values whose meaning they cannot recover — a date and a piece of text look identical once the
 * type is gone — and would write them back as whatever they guessed. Refusing says the one true
 * thing, which is that this fragment cannot be described.</p>
 *
 * <p>A missing variation and a missing fragment are two refusals because two different people make
 * those mistakes: one mistyped a name from a list they were looking at, the other mistyped an
 * address. Telling them apart is the difference between checking a spelling and going to find out
 * which variations exist.</p>
 */
public final class ReadContentFragmentHandler implements CommandHandler {

    /** The node type a content fragment has. */
    public static final String FRAGMENT_TYPE = "dam:Asset";

    /** Where a fragment records which model it is an instance of. */
    public static final String MODEL_PROPERTY = "cq:model";

    /** Where a fragment keeps the elements of its variations. */
    public static final String DATA_NODE = "jcr:content/data";


    /** The category a fragment nothing is at is refused under. */
    public static final String FRAGMENT_NOT_FOUND = "fragment_not_found";

    /** The category a fragment the caller may not read is refused under. */
    public static final String FRAGMENT_ACCESS_DENIED = "fragment_access_denied";

    /** The category something that is there and is not a usable fragment is refused under. */
    public static final String FRAGMENT_INVALID = "fragment_invalid";

    /** The category a variation the fragment does not have is refused under. */
    public static final String VARIATION_NOT_FOUND = "variation_not_found";

    /** The category a fragment larger than this command may answer is refused under. */
    public static final String RESULT_BUDGET_EXCEEDED = "result_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public ReadContentFragmentHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ReadContentFragmentCommand.Outcome asked =
                ReadContentFragmentCommand.of(arguments, contract);
        if (asked instanceof final ReadContentFragmentCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return read(((ReadContentFragmentCommand.Held) asked).command(), resolver, context);
    }

    private Answer read(ReadContentFragmentCommand command, ResourceResolver resolver,
                        CallerContext context) {
        final Resource fragment = resolver.getResource(command.fragmentPath());
        if (fragment == null) {
            return new Failed(FRAGMENT_NOT_FOUND, command.fragmentPath() + " is not there");
        }
        final Resource data = fragment.getChild(DATA_NODE);
        if (data == null) {
            return new Failed(FRAGMENT_INVALID, command.fragmentPath() + " is there and is not a"
                    + " content fragment; what is there is something else");
        }
        final String model = modelOf(fragment);
        if (model.isEmpty()) {
            return new Failed(FRAGMENT_INVALID, command.fragmentPath() + " names no model. The"
                    + " model is what declares what each element is, and the answer names it"
                    + " rather than restating a type beside every value — so a fragment with no"
                    + " model is one whose elements nobody can interpret.");
        }
        final Resource variation = variationOf(data, command.variationName());
        if (variation == null) {
            return new Failed(VARIATION_NOT_FOUND, command.fragmentPath() + " has no variation named "
                    + command.variationName() + "; the fragment is there and that variation is not");
        }
        return answered(command, fragment, model, variation, context);
    }

    private Answer answered(ReadContentFragmentCommand command, Resource fragment, String model,
                            Resource variation, CallerContext context) {
        final List<ReadContentFragmentResult.Element> elements = elementsOf(variation);
        final DocumentValue.Mapping result = ReadContentFragmentResult.documentOf(
                command.fragmentPath(), model, command.variationName(), titleOf(fragment),
                elements);
        if (String.valueOf(result).length() > context.result().limit()) {
            return new Failed(RESULT_BUDGET_EXCEEDED, "this fragment is larger than the "
                    + context.result().limit() + " bytes this command answers with");
        }
        return new Produced(result);
    }

    /**
     * Which model a fragment declares, where it declares one.
     *
     * <p>Read through its own content node rather than assuming one is there. A resource provider
     * can resolve a compound path without the intermediate node being reachable on its own, so the
     * node that resolved the data is not proof that this one resolves too — and a fragment naming
     * no model is a case this command already has an answer for.</p>
     *
     * @param fragment the fragment
     * @return the model's address, or empty where the fragment names none
     */
    public static String modelOf(Resource fragment) {
        return java.util.Optional.ofNullable(fragment.getChild(CONTENT_NODE))
                .map(content -> content.getValueMap().get(MODEL_PROPERTY, ""))
                .orElse("");
    }

    /**
     * What one fragment is called, which is empty where it is called nothing.
     *
     * <p>Read through its own content node for the same reason the model is: a provider can resolve
     * a compound path without the intermediate node being reachable on its own.</p>
     *
     * @param fragment the fragment
     * @return its title, or empty where it has none
     */
    public static String titleOf(Resource fragment) {
        return java.util.Optional.ofNullable(fragment.getChild(CONTENT_NODE))
                .map(content -> content.getValueMap().get(ListChildPagesHandler.TITLE_PROPERTY, ""))
                .orElse("");
    }

    /** Where a fragment keeps its own properties, including the model it is an instance of. */
    public static final String CONTENT_NODE = "jcr:content";

    /**
     * The node holding one named variation's elements.
     *
     * <p>The master variation is the data node itself rather than a child of it, which is how the
     * platform stores it. Treating it like any other name would answer that every fragment lacks
     * the one variation every fragment has.</p>
     *
     * @param data the fragment's data node
     * @param variation the variation's name
     * @return the node holding it, or nothing where the fragment has no such variation
     */
    public static Resource variationOf(Resource data, String variation) {
        return ReadContentFragmentCommand.MASTER_VARIATION.equals(variation)
                ? data : data.getChild(variation);
    }

    /**
     * The elements of one variation, typed by what the repository recorded and rendered by the
     * same mapping the content loader uses.
     *
     * @param variation the node holding the variation's elements
     * @return the elements, in the order the repository holds them
     */
    public static List<ReadContentFragmentResult.Element> elementsOf(Resource variation) {
        final List<ReadContentFragmentResult.Element> elements = new ArrayList<>();
        variation.getValueMap().forEach((name, value) -> {
            if (!name.startsWith("jcr:") && !name.startsWith("sling:")
                    && !name.startsWith("cq:")) {
                elements.add(elementOf(name, value));
            }
        });
        return Collections.unmodifiableList(elements);
    }

    /**
     * One element as a caller receives it, which is text or a list of it.
     *
     * <p>What the repository recorded may be a single value or several, and the difference is kept:
     * an element declared as a list and answered as one string reads as a fragment with a different
     * shape than the model gave it.</p>
     *
     * @param name the element's own name
     * @param value what the repository holds under it
     * @return the element
     */
    private static ReadContentFragmentResult.Element elementOf(String name, Object value) {
        return new ReadContentFragmentResult.Element(name, value instanceof final Object[] several
                ? java.util.Arrays.stream(several).map(String::valueOf).toList()
                : List.of(String.valueOf(value)));
    }


    @Override
    public List<String> categories() {
        return List.of(FRAGMENT_ACCESS_DENIED, FRAGMENT_INVALID, FRAGMENT_NOT_FOUND,
                RESULT_BUDGET_EXCEEDED, VARIATION_NOT_FOUND);
    }
}
