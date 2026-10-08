package rs.slingshot.agent.fixture;

/** A wall-clock difference hidden from the old naming heuristic. */
public final class SubtractionWithoutDurationName {

    /**
     * Compares two readings.
     *
     * @param started the earlier reading
     * @return the difference
     */
    public long measured(long started) {
        return System.currentTimeMillis()
                - started;
    }
}
