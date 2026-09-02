package rs.slingshot.agent.fixture;

/** A file every rule accepts. */
public final class Accepted {

    /** How many attempts one operation makes before it stops. */
    private static final int MAXIMUM_ATTEMPTS = 3;

    private Accepted() {
    }

    /**
     * Whether an attempt count is still inside the ceiling.
     *
     * @param attempts how many attempts have been made
     * @return whether another one is allowed
     */
    public static boolean allowed(int attempts) {
        return attempts < MAXIMUM_ATTEMPTS;
    }
}
