// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which query a continuation token belongs to.
 *
 * <p>It is taken over the canonical bytes of the whole query — the command's own name and every
 * argument it was submitted with — so two queries that differ in anything that changes which rows
 * come back, or the order they come back in, produce different digests. Nothing is left out of the
 * derivation on the grounds that it "does not affect paging", because whether an argument affects
 * paging is a property of a command's implementation and this has to hold for commands that do not
 * exist yet.</p>
 */
public final class QueryDigest {

    /** The version this derivation happens under, which is inside the digest. */
    public static final String VERSION = "slingshot.agent-query/1";

    /** What separates two fields inside the derivation, which no field can carry. */
    public static final byte FIELD_SEPARATOR = 0;

    private final DigestValue value;

    private QueryDigest(DigestValue value) {
        this.value = value;
    }

    /** The result of deriving one: the digest, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A query whose arguments can be written canonically.
     *
     * @param digest the digest of that query
     */
    public record Held(QueryDigest digest) implements Outcome {
    }

    /**
     * A query whose arguments cannot.
     *
     * @param detail what could not be written, and where
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * Derives the digest of one query.
     *
     * @param commandWireName the command being asked
     * @param arguments every argument it was asked with
     * @return the digest, or the one reason there is none
     */
    public static Outcome of(String commandWireName, DocumentValue arguments) {
        final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(arguments);
        if (written instanceof final CanonicalByteWriter.Refused refused) {
            return new Refused(refused.refusal().rendered());
        }
        final ByteArrayOutputStream bound = new ByteArrayOutputStream();
        List.of(VERSION.getBytes(StandardCharsets.UTF_8),
                        commandWireName.getBytes(StandardCharsets.UTF_8),
                        ((CanonicalByteWriter.Written) written).bytes())
                .forEach(field -> {
                    bound.writeBytes(field);
                    bound.write(FIELD_SEPARATOR);
                });
        return new Held(new QueryDigest(Digest.of(bound.toByteArray())));
    }

    /**
     * The digest itself.
     *
     * @return the digest
     */
    public DigestValue value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final QueryDigest digest && value.matches(digest.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.rendered();
    }
}
