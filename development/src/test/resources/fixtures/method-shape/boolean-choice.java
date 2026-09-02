package rs.slingshot.agent.fixture;

import java.util.List;

/** A method taking a choice nobody can read at the call site. */
public final class BooleanChoice {

    private BooleanChoice() {
    }

    /**
     * Applies something, one way or the other.
     *
     * @param refuse whether to refuse
     * @return what it did
     */
    public static String apply(boolean refuse) {
        return refuse ? "refused" : "applied";
    }
}
