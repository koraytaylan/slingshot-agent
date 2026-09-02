package rs.slingshot.agent.fixture;

import java.util.Collections;
import java.util.List;

/** A copy handed out where a view states the same thing. */
public final class CopyWhereAViewWouldDo {

    private final List<String> rows;

    /**
     * Holds rows.
     *
     * @param rows the rows
     */
    public CopyWhereAViewWouldDo(List<String> rows) {
        this.rows = List.copyOf(rows);
    }

    /**
     * Answers the rows.
     *
     * @return the rows
     */
    public List<String> rows() {
        return List.copyOf(rows);
    }
}
