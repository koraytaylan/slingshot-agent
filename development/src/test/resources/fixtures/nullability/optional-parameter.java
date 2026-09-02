package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type that makes its caller build a wrapper. */
public final class OptionalParameter {

    /**
     * Answers the text it was given, if it was given one.
     *
     * @param text the text, wrapped
     * @return the text
     */
    public @NotNull String echo(@NotNull Optional<String> text) {
        return text.orElse("");
    }
}
