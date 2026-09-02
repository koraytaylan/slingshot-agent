// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;

/**
 * What a submission means, derived rather than allocated — and derived here rather than believed.
 *
 * <p>This is the idempotency key. A client that crashed between writing a request and recording its
 * outcome arrives at the same value when it restarts, and this side recognises the resend as the
 * same submission instead of as a second piece of work. That only holds because the value comes out
 * of the request rather than out of a counter.</p>
 *
 * <p>It is derived independently here and compared. Reading the key a client sent and trusting it
 * is letting a caller assert what its own request means, which is the one thing an idempotency key
 * must not be.</p>
 *
 * <p><strong>What this digest does not cover:</strong> the target identity digest and the
 * environment revision. The client's binding version leaves both out, so two submissions against
 * two different targets, or against two different revisions of one target, derive the same digest.
 * They are not the same work, and they are compared where the durable record holds them — beside
 * this digest rather than folded into it. Nobody reading this type should conclude that the digest
 * alone decides that two submissions are the same work.</p>
 */
public final class SubmittedCommandDigest {

    /** The version this derivation happens under, which is inside the digest. */
    public static final String VERSION = "slingshot.submitted-command/1";

    /**
     * What separates two fields inside a derived digest.
     *
     * <p>A byte that cannot occur inside any field: every field is either hexadecimal, a wire name,
     * a semantic version, or canonical bytes, and none of the four can carry a zero byte. That is
     * what stops one arrangement of fields from digesting to the same value as another.</p>
     */
    public static final byte FIELD_SEPARATOR = 0;

    private final DigestValue value;

    private SubmittedCommandDigest(DigestValue value) {
        this.value = value;
    }

    /**
     * Derives the digest of one submission's contracts and arguments.
     *
     * @param identity the five fields that say which command contract this is
     * @param canonicalContractDigest the canonical-form contract the arguments were written under
     * @param transportContractDigest the transport contract the submission travels under
     * @param canonicalArguments the complete canonical argument bytes, exactly as they are sent
     * @return the derived digest
     */
    public static SubmittedCommandDigest derive(CommandContractIdentity identity,
                                                DigestValue canonicalContractDigest,
                                                DigestValue transportContractDigest,
                                                byte[] canonicalArguments) {
        final ByteArrayOutputStream bound = new ByteArrayOutputStream();
        List.of(VERSION,
                        transportContractDigest.rendered(),
                        identity.wireName(),
                        identity.contractVersion(),
                        identity.limitsDigest().rendered(),
                        canonicalContractDigest.rendered(),
                        identity.argumentSchemaDigest().rendered(),
                        identity.resultSchemaDigest().rendered())
                .forEach(field -> separated(bound, field.getBytes(StandardCharsets.UTF_8)));
        separated(bound, canonicalArguments);
        return new SubmittedCommandDigest(Digest.of(bound.toByteArray()));
    }

    private static void separated(ByteArrayOutputStream bound, byte[] field) {
        bound.writeBytes(field);
        bound.write(FIELD_SEPARATOR);
    }

    /**
     * The digest itself.
     *
     * @return the digest
     */
    public DigestValue value() {
        return value;
    }

    /** Why a key a client sent is not the key this side derived. */
    public enum Refusal {
        /** What arrived is not sixty-four lower-case hexadecimal characters. */
        NOT_A_DIGEST,
        /** What arrived is a digest and not this one. */
        NOT_THE_DERIVED_KEY
    }

    /** The result of comparing a key: it matched, or the one reason it did not. */
    public sealed interface Comparison permits Matched, Refused {
    }

    /** A key that is the one derived here. */
    public record Matched() implements Comparison {
    }

    /**
     * A key that is not.
     *
     * @param refusal why it is not
     * @param detail what was observed, naming neither key
     */
    public record Refused(Refusal refusal, String detail) implements Comparison {
    }

    /**
     * Compares a key a client supplied with the one derived here.
     *
     * <p>Neither key appears in the refusal. A refusal that echoed the derived key would hand
     * whoever guessed wrong the answer, and one that echoed the supplied key would read a caller's
     * own bytes back to them out of a log.</p>
     *
     * @param supplied the key the client sent
     * @return whether it is the derived key, or the one reason it is not
     */
    public Comparison compare(String supplied) {
        final DigestValue.Outcome held = DigestValue.of(supplied);
        if (held instanceof DigestValue.Refused) {
            return new Refused(Refusal.NOT_A_DIGEST,
                    "what arrived is not sixty-four lower-case hexadecimal characters");
        }
        if (!((DigestValue.Held) held).digest().matches(value)) {
            return new Refused(Refusal.NOT_THE_DERIVED_KEY,
                    "the key that arrived is not the one this side derived for this submission");
        }
        return new Matched();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final SubmittedCommandDigest digest && value.matches(digest.value);
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
