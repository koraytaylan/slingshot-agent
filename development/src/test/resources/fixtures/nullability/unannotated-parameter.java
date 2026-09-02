package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type whose parameter declares no nullness. */
public final class UnannotatedParameter {

    /**
     * Answers the text it was given.
     *
     * @param text the text
     * @return the text
     */
    public @NotNull String echo(String text) {
        return text;
    }
}
