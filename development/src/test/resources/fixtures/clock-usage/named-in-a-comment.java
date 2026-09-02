package rs.slingshot.agent.fixture;

/**
 * A class explaining why System.currentTimeMillis is never used for an elapsed duration and why
 * System.nanoTime is never read as an instant, and doing neither.
 */
public final class NamedInAComment {

    /**
     * When it happened, taken as an argument the way everything else here takes one.
     *
     * @param atUnixMilliseconds when
     * @return the same instant
     */
    public long happenedAtUnixMilliseconds(long atUnixMilliseconds) {
        // System.currentTimeMillis and an elapsed duration are named here and used nowhere.
        return atUnixMilliseconds;
    }
}
