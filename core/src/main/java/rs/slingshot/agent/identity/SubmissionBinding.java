// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;

/**
 * What one submission declares it will produce, folded into the key that identifies it.
 *
 * <p>The manifest folds in after the contract digest rather than inside it, exactly as the client
 * does it: a submission declaring different artifacts for the same arguments is different work, and
 * it differs without the command contract identity moving. Every part is required — there is no
 * default manifest and no absent count read as zero, because a default is a value nobody chose
 * appearing inside an identity two systems have to agree on.</p>
 *
 * @param kind which shape of manifest this is
 * @param artifactRows how many artifacts at most
 * @param artifactBytes how many bytes at most, across all of them
 */
public record SubmissionBinding(ArtifactManifestKind kind, long artifactRows, long artifactBytes) {

    /** The version this binding happens under, which is inside the key. */
    public static final String VERSION = "slingshot.command-submission/1";

    /**
     * Derives the key that identifies one submission.
     *
     * @param digest the digest of the submission's contracts and arguments
     * @return the idempotency key
     */
    public DigestValue keyFor(SubmittedCommandDigest digest) {
        final ByteArrayOutputStream bound = new ByteArrayOutputStream();
        List.of(VERSION,
                        digest.value().rendered(),
                        kind.spelling(),
                        Long.toString(artifactRows),
                        Long.toString(artifactBytes))
                .forEach(field -> {
                    bound.writeBytes(field.getBytes(StandardCharsets.UTF_8));
                    bound.write(SubmittedCommandDigest.FIELD_SEPARATOR);
                });
        return Digest.of(bound.toByteArray());
    }
}
