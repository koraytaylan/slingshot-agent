package rs.slingshot.agent.fixture;

/** A file with a quantity nobody named. */
public final class MagicNumber {

    private MagicNumber() {
    }

    /**
     * Whether an attempt count is still inside a ceiling nobody named.
     *
     * @param attempts how many attempts have been made
     * @return whether another one is allowed
     */
    public static boolean allowed(int attempts) {
        return attempts < 7;
    }
}
