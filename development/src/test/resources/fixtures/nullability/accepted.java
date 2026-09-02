package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type exercising every permitted form. */
public final class Accepted {

    /** The text this type holds, which is never absent. */
    private final String text;

    /**
     * Holds text that is never absent.
     *
     * @param text the text
     */
    public Accepted(@NotNull String text) {
        this.text = text;
    }

    /**
     * Answers the text.
     *
     * @return the text
     */
    public @NotNull String text() {
        return text;
    }

    /**
     * Answers the text where it is not empty, which is absence modelled as a type.
     *
     * @return the text, or nothing where it is empty
     */
    public @NotNull Optional<String> stated() {
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * Answers how long the text is, which is a primitive and cannot be absent.
     *
     * @return the length
     */
    public int length() {
        return text.length();
    }
}
