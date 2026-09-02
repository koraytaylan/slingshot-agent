package rs.slingshot.agent.fixture;

/** A type whose method leaves a parameter undescribed. */
public final class UndocumentedParameter {

    private UndocumentedParameter() {
    }

    /**
     * Joins two pieces of text.
     *
     * @param first the first piece
     * @return the joined text
     */
    public static String join(String first, String second) {
        return first + second;
    }
}
