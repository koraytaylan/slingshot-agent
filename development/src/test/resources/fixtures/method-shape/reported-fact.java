package rs.slingshot.agent.fixture;

import java.util.List;

/** A method reporting a fact it observed, which needs no name. */
public final class ReportedFact {

    private ReportedFact() {
    }

    /**
     * Whether the text was empty when it was looked at.
     *
     * @param text the text
     * @return whether it was empty
     */
    public static boolean wasEmpty(String text) {
        return text.isEmpty();
    }
}
