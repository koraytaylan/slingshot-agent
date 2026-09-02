package rs.slingshot.agent.fixture;

import java.util.List;

/** A method inside the complexity ceiling and past the nesting one. */
public final class NestingPastCeilingComplexityInside {

    private NestingPastCeilingComplexityInside() {
    }

    /**
     * Answers whether anything was found.
     *
     * @param rows the rows
     * @return whether anything was found
     */
    public static boolean found(List<List<List<String>>> rows) {
        for (final List<List<String>> outer : rows) {
            for (final List<String> middle : outer) {
                for (final String inner : middle) {
                    if (!inner.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
