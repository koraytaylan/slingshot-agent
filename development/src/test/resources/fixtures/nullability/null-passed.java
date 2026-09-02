package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type that passes a null value, through a variable. */
public final class NullPassed {

    /**
     * Passes a null value to something that must not receive one.
     *
     * @return the list it built
     */
    public @NotNull List<String> build() {
        final String absent = null;
        return List.of(String.valueOf(absent), take(absent));
    }

    /**
     * Takes a value that must not be null.
     *
     * @param text the text
     * @return the text
     */
    public @NotNull String take(@NotNull String text) {
        return text;
    }
}
