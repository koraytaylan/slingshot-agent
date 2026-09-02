// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A place to write, and never a way to reach anywhere else.
 *
 * <p>One command needs scratch space while it works, and that is exactly the pressure that would
 * otherwise reopen session acquisition — "just let a handler get a session and it can make its own
 * temporary node". So the framework hands it a place instead of the means to find one: a handle
 * onto a directory the framework chose, opened and released by the framework, that can write
 * nothing else.</p>
 *
 * <p>Every path it is given is resolved inside its own root and compared with it afterwards, so a
 * separator, a parent reference, or an encoding that would leave is refused rather than normalised
 * into something that looks fine. And it exposes no resolver, no session, and no parent: there is
 * nothing on it to reach the repository through.</p>
 */
public final class StagingArea implements AutoCloseable {

    private final Path root;
    private final long bytes;
    private final AtomicLong written = new AtomicLong();

    private StagingArea(Path root, long bytes) {
        this.root = root;
        this.bytes = bytes;
    }

    /** Why a write was refused, each cause distinct because each is a different mistake. */
    public enum Refusal {
        /** The path would leave the one directory this handle is onto. */
        OUTSIDE_ITS_OWN_ROOT,
        /** The write would take this command past the room its own row declared. */
        PAST_ITS_BYTE_BUDGET,
        /** The place could not be written to at all. */
        UNWRITABLE
    }

    /** What one write produced. */
    public sealed interface Outcome permits Written, Refused {
    }

    /**
     * Bytes that are now in this area.
     *
     * @param at where they went, inside this area's own root
     * @param bytes how many
     */
    public record Written(Path at, long bytes) implements Outcome {
    }

    /**
     * No write, and exactly why.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Opens one area for one command, where its row declares room for one.
     *
     * @param under the directory the framework chose, inside the agent's own tree
     * @param row the command's own registry row
     * @return the area, or nothing where the row declares no room
     */
    public static Optional<StagingArea> forRow(Path under, RegistryRow row) {
        if (row.staging() == RegistryRow.Staging.NONE_AT_ALL) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(under);
            return Optional.of(new StagingArea(under.toRealPath(), row.stagingBytes()));
        } catch (final IOException unwritable) {
            throw new UncheckedIOException(under + " could not be opened for one command",
                    unwritable);
        }
    }

    /**
     * Writes something into this area.
     *
     * @param named what to call it, which is resolved inside this area and nowhere else
     * @param content what to write
     * @return where it went, or the one reason it did not
     */
    public Outcome write(String named, byte[] content) {
        final Path at = root.resolve(named).normalize();
        if (!at.startsWith(root)) {
            // Compared after normalising rather than inspected before: a separator, a parent
            // reference and an encoding all look different going in and identical coming out.
            return new Refused(Refusal.OUTSIDE_ITS_OWN_ROOT,
                    named + " resolves outside the one place this command may write");
        }
        if (written.get() + content.length > bytes) {
            return new Refused(Refusal.PAST_ITS_BYTE_BUDGET, "this command declared room for "
                    + bytes + " bytes and this write would take it to "
                    + (written.get() + content.length));
        }
        try {
            Files.createDirectories(java.util.Objects.requireNonNull(at.getParent(),
                    "a name inside this area always has one"));
            Files.write(at, content);
            written.addAndGet(content.length);
            return new Written(at, content.length);
        } catch (final IOException unwritable) {
            return new Refused(Refusal.UNWRITABLE, named + " could not be written");
        }
    }

    /**
     * Writes text into this area.
     *
     * @param named what to call it
     * @param content what to write
     * @return where it went, or the one reason it did not
     */
    public Outcome write(String named, String content) {
        return write(named, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * How much room is left.
     *
     * @return the bytes
     */
    public long remaining() {
        return bytes - written.get();
    }

    /**
     * Whether this area still exists.
     *
     * @return whether anything is still there
     */
    public boolean isOpen() {
        return Files.isDirectory(root);
    }

    /**
     * Gives the place back, on every path a command can end by.
     *
     * <p>Released by the framework rather than by the command: success, every declared failure and
     * every interruption end the same way, because a place left behind on one of those paths is a
     * place left behind forever.</p>
     */
    @Override
    public void close() {
        try (var walked = Files.walk(root)) {
            walked.sorted(java.util.Comparator.reverseOrder()).forEach(StagingArea::remove);
        } catch (final IOException gone) {
            // A place that is already gone is gone, which is what closing asked for.
            written.set(0);
        }
    }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException held) {
            throw new UncheckedIOException(path + " could not be given back", held);
        }
    }
}
