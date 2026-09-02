package rs.slingshot.agent.fixture;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A type with a field that may be absent. */
public final class OptionalField {

    /** A field that has not been designed yet. */
    private Optional<String> text = Optional.empty();

    /**
     * Answers the field.
     *
     * @return the field
     */
    public @NotNull String echo() {
        return text.orElse("");
    }
}
