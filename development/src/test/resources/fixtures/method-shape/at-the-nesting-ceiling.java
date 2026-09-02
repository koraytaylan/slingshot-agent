package rs.slingshot.agent.fixture;

import java.util.List;

/** A method nesting exactly as deep as the ceiling allows. */
public final class AtTheNestingCeiling {

    private AtTheNestingCeiling() {
    }

    /**
     * Counts what it was given.
     *
     * @param rows the rows
     * @return the count
     */
    public static int count(List<String> rows) {
        int counted = 0;
        for (final String row : rows) {
            if (!row.isEmpty()) {
                for (int index = 0; index < row.length(); index++) {
                    counted++;
                }
            }
        }
        return counted;
    }
}
