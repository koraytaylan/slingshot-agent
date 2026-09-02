package rs.slingshot.agent.fixture;

import java.util.List;

/** A method nesting one level past the ceiling. */
public final class OnePastTheNestingCeiling {

    private OnePastTheNestingCeiling() {
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
                    if (row.charAt(index) != ' ') {
                        counted++;
                    }
                }
            }
        }
        return counted;
    }
}
