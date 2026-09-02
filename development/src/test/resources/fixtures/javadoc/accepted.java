package rs.slingshot.agent.fixture;

/** A type every completeness rule accepts. */
public final class Accepted {

    private Accepted() {
    }

    /**
     * Joins two pieces of text with nothing between them.
     *
     * @param first the piece that comes first
     * @param second the piece that comes second
     * @param <T> the kind of thing being described, which is never used here
     * @return the two pieces, joined
     */
    public static <T> String join(String first, String second) {
        return first + second;
    }
}
