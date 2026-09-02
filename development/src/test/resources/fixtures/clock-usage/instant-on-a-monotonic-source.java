package rs.slingshot.agent.fixture;

/** A class that puts a reading with no meaning of its own on the wire as an instant. */
public final class InstantOnAMonotonicSource {

    /**
     * When it happened.
     *
     * @return the instant
     */
    public long happenedAtUnixMilliseconds() {
        return System.nanoTime();
    }
}
