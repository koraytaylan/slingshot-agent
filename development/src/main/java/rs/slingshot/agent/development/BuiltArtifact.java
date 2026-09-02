// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A produced archive, read as bytes rather than as the configuration that was supposed to produce
 * it.
 *
 * <p>Every claim this repository makes about what it ships is a claim about an archive: which
 * packages a bundle imports, whether anything was embedded in it, which class-file version its
 * classes carry, which artifacts a container package holds and where each one installs. All of
 * those are properties of the bytes, and a check that read the build configuration instead would
 * be asserting the intention rather than the result.</p>
 */
public final class BuiltArtifact {

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";

    private static final long CLASS_FILE_MAGIC = 0xCAFEBABEL;

    /** Where the major version sits in a class file, after the magic number and the minor. */
    private static final int MAJOR_VERSION_OFFSET = 6;

    /** The mask that reads one byte out of a signed Java byte. */
    private static final int BYTE_MASK = 0xFF;

    /** How many bits one byte occupies, which is how far each byte of a value is shifted. */
    private static final int BITS_PER_BYTE = 8;

    /** How many bytes the class-file magic number occupies. */
    private static final int MAGIC_NUMBER_BYTES = 4;

    /** How many bytes the class-file major version occupies. */
    private static final int MAJOR_VERSION_BYTES = 2;

    private final Path archive;
    private final List<String> entryNames;
    private final SequencedMap<String, byte[]> entries;

    private BuiltArtifact(Path archive, List<String> entryNames, SequencedMap<String, byte[]> entries) {
        this.archive = archive;
        this.entryNames = entryNames;
        this.entries = entries;
    }

    /**
     * Reads a produced archive.
     *
     * @param archive the jar or content package the build produced
     * @return the archive, read into memory
     * @throws IllegalStateException if the archive is absent, because a check cannot report on
     *     bytes no build produced
     */
    public static BuiltArtifact at(Path archive) {
        if (!Files.isRegularFile(archive)) {
            throw new IllegalStateException("no artifact was produced at " + archive
                    + "; run the reactor build before reading what it made");
        }
        final SequencedMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .forEach(entry -> entries.put(entry.getName(), read(zip, entry)));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new BuiltArtifact(archive, List.copyOf(entries.keySet()), entries);
    }

    /**
     * Where the archive is.
     *
     * @return the archive's path
     */
    public Path archive() {
        return archive;
    }

    /**
     * Every entry the archive holds, sorted, so two reads produce the same list.
     *
     * @return the entry names
     */
    public List<String> entryNames() {
        return Collections.unmodifiableList(entryNames);
    }

    /**
     * Whether the archive holds an entry.
     *
     * @param name the entry name
     * @return {@code true} when the archive holds it
     */
    public boolean holds(String name) {
        return entries.containsKey(name);
    }

    /**
     * One entry's bytes.
     *
     * @param name the entry name
     * @return the bytes, or nothing where the archive holds no such entry
     */
    public Optional<byte[]> entry(String name) {
        return Optional.ofNullable(entries.get(name)).map(bytes -> bytes.clone());
    }

    /**
     * One entry's text, read as the archive's own encoding.
     *
     * @param name the entry name
     * @return the text, or nothing where the archive holds no such entry
     */
    public Optional<String> text(String name) {
        return entry(name).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * One header of the archive's manifest.
     *
     * @param header the header name
     * @return the header's value, or nothing where the manifest does not carry it
     */
    public Optional<String> manifestHeader(String header) {
        return entry(MANIFEST_ENTRY).flatMap(bytes -> {
            try {
                final Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
                return Optional.ofNullable(manifest.getMainAttributes().getValue(header));
            } catch (final IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    /**
     * The class-file major version every class in the archive carries.
     *
     * @return one entry per class, in sorted entry order, holding the major version its bytes
     *     declare
     */
    public SequencedMap<String, Integer> classFileMajorVersions() {
        final SequencedMap<String, Integer> versions = new LinkedHashMap<>();
        entries.forEach((name, bytes) -> {
            if (name.endsWith(".class") && bytes.length > MAJOR_VERSION_OFFSET + 1) {
                versions.put(name, majorVersion(bytes));
            }
        });
        return versions;
    }

    private static int majorVersion(byte[] bytes) {
        if (readUnsigned(bytes, 0, MAGIC_NUMBER_BYTES) != CLASS_FILE_MAGIC) {
            throw new IllegalStateException("an entry named .class does not carry class-file bytes");
        }
        return (int) readUnsigned(bytes, MAJOR_VERSION_OFFSET, MAJOR_VERSION_BYTES);
    }

    /**
     * Reads a big-endian unsigned value out of a class file.
     *
     * @param bytes the class file's bytes
     * @param offset where the value starts
     * @param width how many bytes the value occupies
     * @return the value, widened so that a four-byte value is not read as negative
     */
    private static long readUnsigned(byte[] bytes, int offset, int width) {
        long value = 0;
        for (int index = 0; index < width; index++) {
            value = (value << BITS_PER_BYTE) | (bytes[offset + index] & BYTE_MASK);
        }
        return value;
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) {
        try {
            return zip.getInputStream(entry).readAllBytes();
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
