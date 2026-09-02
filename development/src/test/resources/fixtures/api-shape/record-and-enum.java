package rs.slingshot.agent.fixture;

/** A closed pair: one record and one enum, which is two implementations rather than one. */
public sealed interface Materialised permits Known, Unwritten {
}

/**
 * What is known.
 *
 * @param value what it is
 */
record Known(String value) implements Materialised {
}

/** That nothing is known yet. */
enum Unwritten implements Materialised {
    /** Nothing has happened. */
    NOTHING_HAS_HAPPENED
}
