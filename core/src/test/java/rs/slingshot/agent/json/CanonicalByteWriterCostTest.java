// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical writer pays for each character and each member once.
 *
 * <p>The bounds below sit under the cost measured for the allocating writer and over the cost of
 * one output buffer: a regression that builds a temporary array per character, or re-encodes every
 * member name on every ordering comparison, allocates more than the bound on the same value.</p>
 */
final class CanonicalByteWriterCostTest {

    /** Plain characters in the string workload. */
    private static final int PLAIN_CHARACTERS = 200_000;

    /** Members in the object workload. */
    private static final int OBJECT_MEMBERS = 4_000;

    /**
     * Bytes of thread allocation allowed per plain character.
     *
     * <p>A one-byte temporary array is larger than this once its header is counted, and the
     * allocating writer measured a little over twice this figure. The bound is the whole write,
     * including the buffers the canonical bytes themselves occupy.</p>
     */
    private static final int PLAIN_BYTES_PER_CHARACTER = 24;

    /**
     * Bytes of thread allocation allowed per object member.
     *
     * <p>Encoding every name on every comparison of an ordering sort allocates more than this for
     * a few thousand members. Encoding each name once, then writing it, does not.</p>
     */
    private static final int OBJECT_BYTES_PER_MEMBER = 400;

    @Test
    @DisplayName("a long plain string is written without a temporary buffer per character")
    void aLongPlainStringIsWrittenWithoutABufferPerCharacter() {
        final String body = "a".repeat(PLAIN_CHARACTERS);
        final DocumentValue value = new DocumentValue.Text(body);
        final long allocated = allocatedBy(() -> CanonicalByteWriter.write(value));
        assertTrue(allocated < (long) PLAIN_CHARACTERS * PLAIN_BYTES_PER_CHARACTER,
                "writing " + PLAIN_CHARACTERS + " plain characters allocated " + allocated
                        + " bytes");
        final CanonicalByteWriter.Written written = assertInstanceOf(
                CanonicalByteWriter.Written.class, CanonicalByteWriter.write(value),
                "a plain string was refused");
        assertEquals("\"" + body + "\"", written.rendered(),
                "the plain string was not written as itself");
    }

    @Test
    @DisplayName("a wide object encodes each member name once and keeps byte order")
    void aWideObjectEncodesEachMemberNameOnce() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        for (int index = OBJECT_MEMBERS - 1; index >= 0; index = index - 1) {
            members.put(padded(index), new DocumentValue.Whole(index));
        }
        final DocumentValue value = new DocumentValue.Mapping(members);
        final long allocated = allocatedBy(() -> CanonicalByteWriter.write(value));
        assertTrue(allocated < (long) OBJECT_MEMBERS * OBJECT_BYTES_PER_MEMBER,
                "writing " + OBJECT_MEMBERS + " members allocated " + allocated + " bytes");
        final CanonicalByteWriter.Written written = assertInstanceOf(
                CanonicalByteWriter.Written.class, CanonicalByteWriter.write(value),
                "a wide object was refused");
        final String rendered = written.rendered();
        assertTrue(rendered.startsWith("{\"k0000\":0,"), rendered.substring(0, 12));
        assertTrue(rendered.endsWith("\"k3999\":3999}"),
                rendered.substring(rendered.length() - 14));
    }

    @Test
    @DisplayName("quotes, controls, and every UTF-8 width are still the canonical bytes")
    void escapesAndUtf8WidthsStayCanonical() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put("s", new DocumentValue.Text("\"\\\n café中😀"));
        final CanonicalByteWriter.Written written = assertInstanceOf(
                CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(new DocumentValue.Mapping(members)),
                "a string that needs escapes was refused");
        assertEquals("{\"s\":\"" + "\\\"\\\\" + "\\" + "u000a café中😀\"}", written.rendered(),
                "an escape or a wider character changed spelling");
    }

    private static String padded(int index) {
        final String digits = Integer.toString(index);
        return "k" + "0000".substring(digits.length()) + digits;
    }

    private static long allocatedBy(Runnable workload) {
        final ThreadMXBean thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        thread.setThreadAllocatedMemoryEnabled(true);
        final long caller = Thread.currentThread().threadId();
        workload.run();
        long least = Long.MAX_VALUE;
        for (int round = 0; round < 3; round = round + 1) {
            final long before = thread.getThreadAllocatedBytes(caller);
            workload.run();
            final long allocated = thread.getThreadAllocatedBytes(caller) - before;
            if (allocated < least) {
                least = allocated;
            }
        }
        return least;
    }
}
