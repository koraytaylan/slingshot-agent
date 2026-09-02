package rs.slingshot.agent.fixture;

/** A type whose state anybody can change. */
public final class NonFinalField {

    private String text = "";

    /**
     * Answers the text.
     *
     * @return the text
     */
    public String text() {
        return text;
    }
}
