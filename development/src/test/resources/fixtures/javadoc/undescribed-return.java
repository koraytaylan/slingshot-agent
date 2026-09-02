package rs.slingshot.agent.fixture;

/** A type whose method says nothing about what it answers. */
public final class UndescribedReturn {

    private UndescribedReturn() {
    }

    /**
     * Joins two pieces of text.
     *
     * @param first the first piece
     * @param second the second piece
     */
    public static String join(String first, String second) {
        return first + second;
    }
}
