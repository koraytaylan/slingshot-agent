package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type that returns a null value. */
public final class NullReturned {

    /**
     * Answers nothing at all.
     *
     * @return nothing
     */
    public @NotNull String echo() {
        return null;
    }
}
