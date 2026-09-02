package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type whose return declares no nullness. */
public final class UnannotatedReturn {

    /**
     * Answers the text it was given.
     *
     * @param text the text
     * @return the text
     */
    public String echo(@NotNull String text) {
        return text;
    }
}
