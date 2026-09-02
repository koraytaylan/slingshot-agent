package rs.slingshot.agent.fixture;

import java.util.List;

/** The same logic written as a guard clause. */
public final class GuardedRewrite {

    private GuardedRewrite() {
    }

    /**
     * Answers a description of the text.
     *
     * @param text the text
     * @return the description
     */
    public static String describe(String text) {
        if (text.isEmpty()) {
            return "empty";
        }
        return "text of " + text.length();
    }
}
