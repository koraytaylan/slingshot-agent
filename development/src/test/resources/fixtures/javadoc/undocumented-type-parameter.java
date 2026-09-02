package rs.slingshot.agent.fixture;

/** A type whose method leaves a type parameter undescribed. */
public final class UndocumentedTypeParameter {

    private UndocumentedTypeParameter() {
    }

    /**
     * Answers what it was given.
     *
     * @param value the value
     * @return the value
     */
    public static <T> T echo(T value) {
        return value;
    }
}
