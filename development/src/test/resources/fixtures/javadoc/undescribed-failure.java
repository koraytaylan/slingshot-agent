package rs.slingshot.agent.fixture;

/** A type whose method declares a failure it does not describe. */
public final class UndescribedFailure {

    private UndescribedFailure() {
    }

    /**
     * Reads something, or does not.
     *
     * @param text the text
     * @return the text
     */
    public static String read(String text) throws java.io.IOException {
        if (text.isEmpty()) {
            throw new java.io.IOException("nothing to read");
        }
        return text;
    }
}
