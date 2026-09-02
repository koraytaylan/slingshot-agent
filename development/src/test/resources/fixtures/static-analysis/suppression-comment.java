package rs.slingshot.agent.fixture;

/** A file that switches rules off with comments. */
public final class SuppressionComment {

    /**
     * Does nothing, loudly.
     */
    public void act() {
        // CHECKSTYLE:OFF
        final int value = 1; // NOPMD
        // NOSONAR
        assert value == 1;
    }
}
