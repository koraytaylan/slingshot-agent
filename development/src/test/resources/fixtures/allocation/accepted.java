package rs.slingshot.agent.fixture;

import java.util.Collections;
import java.util.List;

/** A type every allocation rule accepts. */
public final class Accepted {

    private final List<String> rows;

    /**
     * Holds rows.
     *
     * @param rows the rows
     */
    public Accepted(List<String> rows) {
        this.rows = List.copyOf(rows);
    }

    /**
     * Answers the rows as a view rather than as a copy.
     *
     * @return the rows
     */
    public List<String> rows() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * Counts the rows that carry text, said as the transformation it is.
     *
     * @return the count
     */
    public long carrying() {
        return rows.stream().filter(row -> !row.isEmpty()).count();
    }
}
