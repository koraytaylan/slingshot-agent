package rs.slingshot.agent.fixture;

import java.util.Collections;
import java.util.List;

/** A transformation written as an iteration order nobody needed. */
public final class IndexedLoop {

    private IndexedLoop() {
    }

    /**
     * Counts the rows that carry text.
     *
     * @param rows the rows
     * @return the count
     */
    public static int carrying(List<String> rows) {
        int counted = 0;
        for (int index = 0; index < rows.size(); index++) {
            if (!rows.get(index).isEmpty()) {
                counted++;
            }
        }
        return counted;
    }
}
