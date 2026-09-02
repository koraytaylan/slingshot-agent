// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import java.util.Optional;
import rs.slingshot.agent.digest.CommittedResource;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;

/**
 * The one route from committed bytes to something an identity may be assembled from.
 *
 * <p>Four steps, in one order, and the order is structural rather than remembered: each step takes
 * a value only the step before it can produce, so there is no call sequence that reaches the third
 * without the first. The first failure is the one reported — a caller told about a later failure
 * would go and fix the wrong thing.</p>
 *
 * <p>Nothing here discloses the bytes it authenticated. A failure names the digest it compared and
 * the digest it found, which is everything somebody needs to work out what drifted and nothing they
 * could not have computed themselves.</p>
 */
public final class CanonicalContractAuthority {

    /** The member a role schema names the canonical-form contract it was written under in. */
    public static final String ANNOTATION = "x-slingshot-canonical-json-contract-sha256";

    /** The member a role schema says what it describes in. */
    public static final String IDENTIFIER = "$id";

    /** How a schema's identifier spells the command and role it describes. */
    private static final String IDENTIFIER_PREFIX = "urn:slingshot:command:";

    private final DigestValue contractDigest;

    private CanonicalContractAuthority(DigestValue contractDigest) {
        this.contractDigest = contractDigest;
    }

    /** The result of the first step: an authority, or the one reason there is none. */
    public sealed interface Outcome permits Authenticated, Refused {
    }

    /**
     * A contract whose bytes are the ones committed for it.
     *
     * @param authority what the steps after this one are taken through
     */
    public record Authenticated(CanonicalContractAuthority authority) implements Outcome {
    }

    /** The result of the second step: an annotated schema, or the one reason there is none. */
    public sealed interface SchemaOutcome permits Annotated, Refused {
    }

    /**
     * A schema that names the contract this authority authenticated.
     *
     * @param schema what the step after this one is taken through
     */
    public record Annotated(AnnotatedSchema schema) implements SchemaOutcome {
    }

    /** The result of the third step: a believed schema, or the one reason there is none. */
    public sealed interface BelievedOutcome permits Believed, Refused {
    }

    /**
     * A schema whose own bytes are the ones committed for it.
     *
     * @param authority what an identity may be assembled from
     */
    public record Believed(RoleAuthority authority) implements BelievedOutcome {
    }

    /** The result of the fourth step: the material, or the one reason there is none. */
    public sealed interface MaterialOutcome permits Assembled, Refused {
    }

    /**
     * A schema that describes the command and role it is being used for.
     *
     * @param material what an identity is assembled from
     */
    public record Assembled(IdentityMaterial material) implements MaterialOutcome {
    }

    /**
     * A step that did not pass, which stops the ones after it.
     *
     * @param step which of the four it was
     * @param detail what was compared and what was found, naming digests rather than bytes
     */
    public record Refused(AuthenticationStep step, String detail)
            implements Outcome, SchemaOutcome, BelievedOutcome, MaterialOutcome {
    }

    /**
     * Step one: the committed canonical-form contract's bytes, against the digest beside them.
     *
     * @param contract the committed contract bytes
     * @param committedDigest the digest committed beside them, in lower-case hexadecimal
     * @return the authority, or the one reason there is none
     */
    public static Outcome authenticate(byte[] contract, String committedDigest) {
        final CommittedResource.Outcome outcome =
                CommittedResource.authenticate(contract, committedDigest);
        if (outcome instanceof final CommittedResource.Refused refused) {
            return new Refused(AuthenticationStep.CONTRACT_BYTES,
                    refused.failure() + ": " + refused.detail());
        }
        return new Authenticated(new CanonicalContractAuthority(
                ((CommittedResource.Loaded) outcome).resource().digest()));
    }

    /**
     * The digest of the contract this authority authenticated.
     *
     * @return the digest
     */
    public DigestValue contractDigest() {
        return contractDigest;
    }

    /**
     * Step two: a role schema's annotation, against the contract this authority authenticated.
     *
     * @param roleSchema the schema's own bytes
     * @param bounds the bounds the schema is read under
     * @return the annotated schema, or the one reason there is none
     */
    public SchemaOutcome annotated(byte[] roleSchema, BoundedDocumentReader.Bounds bounds) {
        final BoundedDocumentReader.Outcome read =
                BoundedDocumentReader.read(roleSchema, bounds);
        if (read instanceof final BoundedDocumentReader.Refused refused) {
            return new Refused(AuthenticationStep.SCHEMA_ANNOTATION,
                    "the schema is not a document: " + refused.refusal().rendered());
        }
        final DocumentValue value = ((BoundedDocumentReader.Read) read).value();
        final Optional<String> named = member(value, ANNOTATION);
        if (named.isEmpty()) {
            return new Refused(AuthenticationStep.SCHEMA_ANNOTATION,
                    "the schema names no contract, and " + contractDigest.rendered()
                            + " is the one it would have had to name");
        }
        if (!named.get().equals(contractDigest.rendered())) {
            return new Refused(AuthenticationStep.SCHEMA_ANNOTATION, "the schema names "
                    + named.get() + " and the contract authenticated here is "
                    + contractDigest.rendered());
        }
        return new Annotated(new AnnotatedSchema(this, value));
    }

    private static Optional<String> member(DocumentValue value, String name) {
        if (!(value instanceof final DocumentValue.Mapping mapping)) {
            return Optional.empty();
        }
        return mapping.member(name)
                .filter(DocumentValue.Text.class::isInstance)
                .map(text -> ((DocumentValue.Text) text).value());
    }

    /**
     * A schema that named the contract this authority authenticated, and nothing more yet.
     *
     * <p>It can only be produced by {@link CanonicalContractAuthority#annotated}, which is what
     * makes the second step unskippable rather than merely documented.</p>
     */
    public static final class AnnotatedSchema {

        private final CanonicalContractAuthority authority;
        private final DocumentValue schema;

        private AnnotatedSchema(CanonicalContractAuthority authority, DocumentValue schema) {
            this.authority = authority;
            this.schema = schema;
        }

        /**
         * Step three: the schema's own canonical bytes, against the digest committed for it.
         *
         * @param committedDigest the digest committed for this schema, in lower-case hexadecimal
         * @return the believed schema, or the one reason there is none
         */
        public BelievedOutcome believed(String committedDigest) {
            final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(schema);
            if (written instanceof final CanonicalByteWriter.Refused refused) {
                return new Refused(AuthenticationStep.SCHEMA_DIGEST,
                        "the schema cannot be written canonically: " + refused.refusal().rendered());
            }
            final DigestValue actual =
                    Digest.of(((CanonicalByteWriter.Written) written).bytes());
            final DigestValue.Outcome held = DigestValue.of(committedDigest);
            if (held instanceof final DigestValue.Refused refused) {
                return new Refused(AuthenticationStep.SCHEMA_DIGEST,
                        "what is committed for this schema is not a digest: " + refused.detail());
            }
            final DigestValue committed = ((DigestValue.Held) held).digest();
            if (!actual.matches(committed)) {
                return new Refused(AuthenticationStep.SCHEMA_DIGEST, "the schema's canonical bytes"
                        + " digest to " + actual.rendered() + " and not to " + committed.rendered());
            }
            return new Believed(new RoleAuthority(authority, schema, actual));
        }
    }

    /**
     * A schema believed on its own digest, which is the only thing an identity is assembled from.
     *
     * <p>It can only be produced by {@link AnnotatedSchema#believed}, which can only be produced by
     * {@link CanonicalContractAuthority#annotated}, which can only be produced by
     * {@link CanonicalContractAuthority#authenticate}. That chain is the order, written where the
     * compiler keeps it.</p>
     */
    public static final class RoleAuthority {

        private final CanonicalContractAuthority authority;
        private final DocumentValue schema;
        private final DigestValue roleDigest;

        private RoleAuthority(CanonicalContractAuthority authority, DocumentValue schema,
                              DigestValue roleDigest) {
            this.authority = authority;
            this.schema = schema;
            this.roleDigest = roleDigest;
        }

        /**
         * The digest of this role's own canonical bytes.
         *
         * @return the digest
         */
        public DigestValue roleDigest() {
            return roleDigest;
        }

        /**
         * Step four: what this schema says it describes, against what it is being used for.
         *
         * @param commandName the command an identity is being assembled for
         * @param role the role of that command the schema is supposed to describe
         * @return the material an identity is assembled from, or the one reason there is none
         */
        public MaterialOutcome permitting(String commandName, String role) {
            final String expected = IDENTIFIER_PREFIX + commandName + ":" + role + ":";
            final Optional<String> declared = member(schema, IDENTIFIER);
            if (declared.isEmpty()) {
                return new Refused(AuthenticationStep.IDENTITY_ASSEMBLY,
                        "the schema says nothing about what it describes");
            }
            if (!declared.get().startsWith(expected)) {
                return new Refused(AuthenticationStep.IDENTITY_ASSEMBLY, "the schema describes "
                        + declared.get() + " and is being used for " + expected);
            }
            return new Assembled(new IdentityMaterial(commandName, role, roleDigest,
                    authority.contractDigest()));
        }
    }

    /**
     * Everything about one role of one command that four steps have established.
     *
     * @param commandName the command's own wire name
     * @param role which of the command's two schemas this is
     * @param roleDigest the digest of that schema's canonical bytes
     * @param contractDigest the digest of the canonical-form contract it was written under
     */
    public record IdentityMaterial(String commandName, String role, DigestValue roleDigest,
                                   DigestValue contractDigest) {
    }
}
