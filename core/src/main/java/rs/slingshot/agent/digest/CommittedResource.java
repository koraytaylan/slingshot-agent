// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.digest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Bytes embedded in this bundle, and the digest committed beside them.
 *
 * <p>Nothing here answers bytes that did not authenticate. A resource and its digest are read
 * together, compared, and either handed over whole or refused with a reason — there is no path on
 * which a caller holds the bytes and the verdict separately, because that is the arrangement in
 * which somebody eventually uses the first and forgets the second.</p>
 */
public final class CommittedResource {

    private final byte[] bytes;

    private CommittedResource(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Why a committed resource produced no bytes. */
    public enum Failure {
        /** The resource or the digest beside it is not embedded in this bundle. */
        NOT_EMBEDDED,
        /** The digest committed beside the resource is not a digest at all. */
        NOT_A_DIGEST,
        /** The bytes do not digest to what is committed beside them. */
        NOT_AUTHENTIC
    }

    /** The result of loading one: the bytes, or the one reason there are none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A resource whose bytes authenticated against the digest committed beside them.
     *
     * @param resource the authenticated resource
     */
    public record Loaded(CommittedResource resource) implements Outcome {
    }

    /**
     * A load that produced no bytes.
     *
     * @param failure why there are none
     * @param detail what was observed, so the cause is readable rather than inferred
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Loads an embedded resource and authenticates it against an embedded digest.
     *
     * @param resource the absolute resource path of the content
     * @param digestResource the absolute resource path of the digest committed beside it
     * @return the authenticated bytes, or the one reason there are none
     */
    public static Outcome load(String resource, String digestResource) {
        final Optional<byte[]> content = embedded(resource);
        final Optional<byte[]> committed = embedded(digestResource);
        if (content.isEmpty() || committed.isEmpty()) {
            return new Refused(Failure.NOT_EMBEDDED,
                    resource + " or " + digestResource + " is not embedded in this bundle");
        }
        return authenticate(content.get(),
                new String(committed.get(), java.nio.charset.StandardCharsets.UTF_8).strip());
    }

    /**
     * Authenticates bytes against a rendered digest.
     *
     * @param content the bytes
     * @param rendered the digest committed beside them, in lower-case hexadecimal
     * @return the authenticated bytes, or the one reason there are none
     */
    public static Outcome authenticate(byte[] content, String rendered) {
        final DigestValue.Outcome held = DigestValue.of(rendered);
        if (held instanceof final DigestValue.Refused refused) {
            return new Refused(Failure.NOT_A_DIGEST,
                    refused.refusal() + ": " + refused.detail());
        }
        final DigestValue committed = ((DigestValue.Held) held).digest();
        final DigestValue actual = Digest.of(content);
        if (!actual.matches(committed)) {
            return new Refused(Failure.NOT_AUTHENTIC, "the bytes digest to " + actual.rendered()
                    + " and not to " + committed.rendered());
        }
        return new Loaded(new CommittedResource(content.clone()));
    }

    /**
     * The authenticated bytes.
     *
     * @return the bytes, as a copy nothing else holds
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * The digest these bytes authenticated against.
     *
     * @return the digest
     */
    public DigestValue digest() {
        return Digest.of(bytes);
    }

    private static Optional<byte[]> embedded(String resource) {
        try (InputStream stream = CommittedResource.class.getResourceAsStream(resource)) {
            return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
        } catch (final IOException unreadable) {
            return Optional.empty();
        }
    }
}
