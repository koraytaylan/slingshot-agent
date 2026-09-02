// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Which elements a content fragment model declares.
 *
 * <p>Read from the model rather than remembered, because the model is what the authoring tools read
 * too. A list kept here would be a second opinion about somebody else's model, and the day the two
 * disagree is the day a fragment written through this agent opens with a field the editor has never
 * heard of.</p>
 *
 * <p>A model whose declaration cannot be found at all is refused rather than treated as declaring
 * everything. Treating it as permissive is how an element name with a typo in it becomes a property
 * that sits in the repository forever, matching nothing and read by nobody.</p>
 */
public final class FragmentModel {

    private final String modelPath;
    private final List<String> elementNames;

    private FragmentModel(String modelPath, List<String> elementNames) {
        this.modelPath = modelPath;
        this.elementNames = List.copyOf(elementNames);
    }

    /**
     * Where the model is.
     *
     * @return its address
     */
    public String modelPath() {
        return modelPath;
    }

    /**
     * Which elements a fragment made from this model has.
     *
     * @return their names, which nothing may add to
     */
    public List<String> elementNames() {
        return Collections.unmodifiableList(elementNames);
    }

    /**
     * The first named element this model has never heard of.
     *
     * @param asked what the caller wants to set
     * @return the first name this model does not declare, or nothing where it declares them all
     */
    public Optional<String> unknownIn(FragmentElements asked) {
        return asked.values().keySet().stream()
                .filter(name -> !elementNames.contains(name))
                .findFirst();
    }

    /** What reading a model produced: the model, or the one reason there is none. */
    public sealed interface Outcome permits Read, Missing, Invalid {
    }

    /**
     * A model this build understands.
     *
     * @param model what it declares
     */
    public record Read(FragmentModel model) implements Outcome {
    }

    /**
     * Nothing is at the address.
     *
     * @param modelPath where nothing is
     */
    public record Missing(String modelPath) implements Outcome {
    }

    /**
     * Something is at the address and it does not declare elements.
     *
     * @param modelPath where it is
     * @param detail what is wrong with it, said as somebody would go and check it
     */
    public record Invalid(String modelPath, String detail) implements Outcome {
    }

    /**
     * Reads one model.
     *
     * @param session the caller's own session, so a model they cannot see is one that is not there
     * @param modelPath where the model is
     * @return what it declares, or the one reason it declares nothing
     */
    public static Outcome at(ResourceResolver session, String modelPath) {
        final Resource model = session.getResource(modelPath);
        if (model == null) {
            return new Missing(modelPath);
        }
        final Resource items = session.getResource(
                modelPath + "/" + FragmentHandlers.MODEL_ELEMENTS);
        if (items == null) {
            return new Invalid(modelPath, "is there and declares no elements at "
                    + FragmentHandlers.MODEL_ELEMENTS + "; what is there is something other than a"
                    + " content fragment model");
        }
        final List<String> names = new ArrayList<>();
        items.getChildren().forEach(item -> names.add(item.getName()));
        return names.isEmpty()
                ? new Invalid(modelPath, "declares no elements at all, and a fragment made from it"
                        + " would hold nothing")
                : new Read(new FragmentModel(modelPath, names));
    }
}
