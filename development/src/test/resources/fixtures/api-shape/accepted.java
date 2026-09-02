package rs.slingshot.agent.fixture;

/** A type every shape rule accepts. */
public final class Accepted {

    /** A named constant, which is data rather than state. */
    public static final String NAME = "accepted";

    private final String text;

    /**
     * Holds text.
     *
     * @param text the text
     */
    public Accepted(String text) {
        this.text = text;
    }

    /**
     * Answers the text.
     *
     * @return the text
     */
    public String text() {
        return text;
    }
}
