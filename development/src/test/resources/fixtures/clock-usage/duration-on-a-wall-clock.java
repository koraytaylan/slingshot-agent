package rs.slingshot.agent.fixture;

/** A class that measures how long something took with a clock somebody corrects. */
public final class DurationOnAWallClock {

    /**
     * How long it took.
     *
     * @param startedAt when it began
     * @return the duration
     */
    public long elapsedSince(long startedAt) {
        final long elapsed = System.currentTimeMillis() - startedAt;
        return elapsed;
    }
}
