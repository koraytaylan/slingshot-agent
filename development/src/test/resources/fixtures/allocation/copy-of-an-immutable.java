package rs.slingshot.agent.fixture;

import java.util.Collections;
import java.util.List;

/** A copy of something nothing could have changed. */
public final class CopyOfAnImmutable {

    private CopyOfAnImmutable() {
    }

    /**
     * Answers a list nothing can change, copied from a list nothing could change.
     *
     * @return the list
     */
    public static List<String> rows() {
        return List.copyOf(List.of("first", "second"));
    }
}
