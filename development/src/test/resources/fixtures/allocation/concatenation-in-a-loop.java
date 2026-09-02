package rs.slingshot.agent.fixture;

import java.util.Collections;
import java.util.List;

/** Text rebuilt whole on every turn of a loop. */
public final class ConcatenationInALoop {

    private ConcatenationInALoop() {
    }

    /**
     * Joins the rows.
     *
     * @param rows the rows
     * @return the joined rows
     */
    public static String join(List<String> rows) {
        String joined = "";
        for (final String row : rows) {
            joined += row;
        }
        return joined;
    }
}
