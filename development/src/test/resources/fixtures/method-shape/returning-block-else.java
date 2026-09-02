package rs.slingshot.agent.fixture;

import java.util.List;

/** A method whose else follows a block that always returns. */
public final class ReturningBlockElse {

    private ReturningBlockElse() {
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
        } else {
            return "text of " + text.length();
        }
    }
}
