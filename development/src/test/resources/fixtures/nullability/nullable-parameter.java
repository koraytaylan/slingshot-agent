package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type that accepts an absent argument. */
public final class NullableParameter {

    /**
     * Answers the text it was given, if it was given one.
     *
     * @param text the text, or nothing
     * @return the text
     */
    public @NotNull String echo(@Nullable String text) {
        return String.valueOf(text);
    }
}
