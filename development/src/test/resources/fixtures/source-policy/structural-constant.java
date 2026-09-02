package rs.slingshot.agent.fixture;

/** A file holding numbers that equal a bound and state none. */
public final class StructuralConstant {

    /** How many milliseconds a second is, where a header is written in seconds. */
    private static final long MILLISECONDS_IN_A_SECOND = 1000;

    /** How much of an input one read takes, which any size answers. */
    private static final int READ_BUFFER_BYTES = 16384;

    private StructuralConstant() {
    }

    /**
     * The seconds one duration is.
     *
     * @param milliseconds the duration
     * @return the seconds, which is what a header carries
     */
    public static long seconds(long milliseconds) {
        return milliseconds / MILLISECONDS_IN_A_SECOND;
    }

    /**
     * How much of an input one read takes.
     *
     * @return the count
     */
    public static int readBuffer() {
        return READ_BUFFER_BYTES;
    }
}
