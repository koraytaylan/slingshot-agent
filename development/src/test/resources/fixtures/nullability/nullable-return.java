package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type that returns an absent value. */
public final class NullableReturn {

    /**
     * Answers nothing in particular.
     *
     * @param text the text
     * @return the text, or nothing
     */
    public @Nullable String echo(@NotNull String text) {
        return text;
    }
}
