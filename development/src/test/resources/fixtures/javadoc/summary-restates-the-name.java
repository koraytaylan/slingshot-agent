package rs.slingshot.agent.fixture;

/** A type whose summary spells its own name back. */
public final class SummaryRestatesTheName {

    private SummaryRestatesTheName() {
    }

    /**
     * The joined text.
     *
     * @param first the first piece
     * @param second the second piece
     * @return the joined text
     */
    public static String joinedText(String first, String second) {
        return first + second;
    }
}
