package rs.slingshot.agent.fixture;

import java.util.Collections;
import java.util.List;

/** A pipeline allocated once per unit of input. */
public final class StreamInASensitivePath {

    private StreamInASensitivePath() {
    }

    /**
     * Reads one byte-level value, allocating a pipeline to do it.
     *
     * @param bytes the bytes
     * @return the count of bytes that carry anything
     */
    public static long readUnsigned(List<Integer> bytes) {
        return bytes.stream().filter(value -> value != 0).count();
    }
}
