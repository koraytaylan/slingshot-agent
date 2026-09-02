package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type annotating something the language already decides. */
public final class RedundantAnnotation {

    /**
     * Answers the count it was given.
     *
     * @param count the count
     * @return the count
     */
    public @NotNull int count(@NotNull int count) {
        return count;
    }
}
