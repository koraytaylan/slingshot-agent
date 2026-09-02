// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * What has to be supplied before anything may be published, and what refuses until it is.
 *
 * <p>Two things are kept apart here that look the same from a distance. A group identifier that
 * reverses a domain the owner holds is <em>verifiable</em>: a registry could check it. A completed
 * namespace verification is a fact about that registry, recorded by the owner who did it. The
 * boundary gates on the second, so a well-formed identifier is refused publication exactly as
 * firmly as a malformed one until the record exists.</p>
 *
 * <p>Packaging is never blocked. A container package an operator can install builds in every state,
 * because the thing being gated is publication and not installation.</p>
 */
public final class PublicationBoundary {

    private static final String METADATA_FILE = "support/publication-metadata.toml";

    private static final String TARGET_ROWS = "target";

    /** The property the build expresses "nothing is published" through. */
    private static final String DEPLOY_SKIP_PROPERTY = "maven.deploy.skip";

    private final String group;
    private final String domain;
    private final NamespaceRecord namespaceRecord;
    private final String verificationReference;
    private final List<Field> fields;
    private final List<TargetRow> targets;

    private PublicationBoundary(String group, String domain, NamespaceRecord namespaceRecord,
                                String verificationReference, List<Field> fields,
                                List<TargetRow> targets) {
        this.group = group;
        this.domain = domain;
        this.namespaceRecord = namespaceRecord;
        this.verificationReference = verificationReference;
        this.fields = fields;
        this.targets = targets;
    }

    /** Whether the registry's own namespace verification has been recorded by the owner. */
    public enum NamespaceRecord {
        /** The owner has completed the registry's verification and recorded it. */
        RECORDED,
        /** No record exists, which is the state a well-formed identifier is still refused in. */
        ABSENT
    }

    /** Whether a target is gated by the namespace record. */
    public enum NamespaceGate {
        /** The record gates this target, because the target is a registry that checks it. */
        REQUIRED,
        /** The record does not gate this target, because nobody there checks a namespace. */
        NOT_REQUIRED
    }

    /**
     * One owner-supplied field, and whether the owner has supplied it.
     *
     * @param key the dotted key the metadata declares it under
     * @param value what the owner supplied, empty where they have not
     */
    public record Field(String key, String value) {

        /**
         * Whether an owner has supplied this field.
         *
         * @return {@code true} when the field carries a value
         */
        public boolean supplied() {
            return !value.isBlank();
        }
    }

    /**
     * One place this product may be published, and what that place requires.
     *
     * @param identifier the target's own name
     * @param audience who reaches the artifact through this target
     * @param namespaceGate whether the namespace record gates this target
     * @param requiredFields the owner-supplied fields this target needs
     */
    public record TargetRow(String identifier, String audience, NamespaceGate namespaceGate,
                            List<String> requiredFields) {

        /**
         * Whether the namespace record gates this target.
         *
         * @return whether a publish here waits on the registry's own verification
         */
        public boolean requiresNamespaceVerification() {
            return namespaceGate == NamespaceGate.REQUIRED;
        }

        /**
         * Holds a target whose required fields nothing can change afterwards.
         */
        public TargetRow {
            requiredFields = List.copyOf(requiredFields);
        }

        /**
         * The owner-supplied fields this target needs before anything may be published to it.
         *
         * @return the field keys, as a view nothing can change
         */
        @Override
        public List<String> requiredFields() {
            return Collections.unmodifiableList(requiredFields);
        }
    }

    /** Whether a target may be published to, or the reasons it may not. */
    public sealed interface Verdict permits Publishable, Withheld {
    }

    /**
     * A target whose every precondition is met.
     *
     * @param target the target
     */
    public record Publishable(String target) implements Verdict {
    }

    /**
     * A target that is refused, with every reason named.
     *
     * @param target the target
     * @param reasons what is absent, one entry per absent precondition
     */
    public record Withheld(String target, List<String> reasons) implements Verdict {

        /**
         * Holds a refusal whose reasons nothing can change afterwards.
         */
        public Withheld {
            reasons = List.copyOf(reasons);
        }

        /**
         * Why the target is refused, one entry per absent precondition.
         *
         * @return the reasons, as a view nothing can change
         */
        @Override
        public List<String> reasons() {
            return Collections.unmodifiableList(reasons);
        }
    }

    /** The result of reading the metadata: the boundary, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * Metadata that satisfied its shape completely.
     *
     * @param boundary the loaded boundary
     */
    public record Loaded(PublicationBoundary boundary) implements Outcome {
    }

    /**
     * A read that produced no boundary.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the publication metadata is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("publication-metadata")
                .text("namespace.group")
                .text("namespace.domain")
                .answer("namespace.verified")
                .text("namespace.verification_reference")
                .text("repository.url")
                .text("repository.connection")
                .text("developer.name")
                .text("developer.identifier")
                .text("signing.identity")
                .text("signing.public_key_fingerprint")
                .rows(TARGET_ROWS, row -> row.text("id").text("audience")
                        .answer("requires_namespace_verification").textList("required_fields"))
                .build();
    }

    /**
     * Reads the metadata this repository commits.
     *
     * @param root the repository root
     * @return the boundary, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readMetadata(root.resolve(METADATA_FILE));
    }

    /**
     * Reads a metadata document from wherever it sits.
     *
     * @param metadata the metadata document
     * @return the boundary, or the one reason the document was refused
     */
    public static Outcome readMetadata(Path metadata) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(metadata, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final List<Field> fields = List.of(
                new Field("repository.url", document.text("repository.url")),
                new Field("repository.connection", document.text("repository.connection")),
                new Field("developer.name", document.text("developer.name")),
                new Field("developer.identifier", document.text("developer.identifier")),
                new Field("signing.identity", document.text("signing.identity")),
                new Field("signing.public_key_fingerprint",
                        document.text("signing.public_key_fingerprint")));
        return new Loaded(new PublicationBoundary(
                document.text("namespace.group"),
                document.text("namespace.domain"),
                document.answer("namespace.verified")
                        ? NamespaceRecord.RECORDED : NamespaceRecord.ABSENT,
                document.text("namespace.verification_reference"),
                fields,
                document.rows(TARGET_ROWS).stream()
                        .map(row -> new TargetRow(row.text("id"), row.text("audience"),
                                row.answer("requires_namespace_verification")
                                        ? NamespaceGate.REQUIRED : NamespaceGate.NOT_REQUIRED,
                                row.textList("required_fields")))
                        .toList()));
    }

    /**
     * Every target this product declares.
     *
     * @return the target rows, in the document's own order
     */
    public List<TargetRow> targets() {
        return Collections.unmodifiableList(targets);
    }

    /**
     * Whether the group identifier reverses the domain it claims.
     *
     * @return one finding where it does not, so a coordinate and the namespace it claims cannot
     *     disagree
     */
    public Optional<PolicyFinding> namespaceShape() {
        final String reversed = String.join(".", reversedParts(domain));
        if (reversed.equals(group)) {
            return Optional.empty();
        }
        return Optional.of(PolicyFinding.inFile(METADATA_FILE, "publication-namespace",
                group + " does not reverse " + domain));
    }

    /**
     * Whether one target may be published to.
     *
     * @param identifier the target's own name
     * @return the verdict, with every absent precondition named where it is withheld
     */
    public Verdict verdict(String identifier) {
        final TargetRow target = targets.stream()
                .filter(row -> row.identifier().equals(identifier))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no target is named " + identifier));
        final List<String> reasons = new ArrayList<>();
        final boolean recorded = namespaceRecord == NamespaceRecord.RECORDED;
        if (target.requiresNamespaceVerification() && !recorded) {
            reasons.add("namespace.verified is not set for " + identifier);
        }
        if (target.requiresNamespaceVerification() && recorded && verificationReference.isBlank()) {
            reasons.add("namespace.verification_reference is absent for " + identifier);
        }
        target.requiredFields().stream()
                .filter(key -> !supplied(key))
                .map(key -> key + " is absent for " + identifier)
                .forEach(reasons::add);
        namespaceShape().map(finding -> finding.symbol() + " for " + identifier)
                .ifPresent(reasons::add);
        return reasons.isEmpty()
                ? new Publishable(identifier)
                : new Withheld(identifier, List.copyOf(reasons));
    }

    /**
     * Whether the build expresses the boundary the metadata records.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module the build would publish while no target is publishable
     */
    public PolicyReport against(ReactorModel reactor) {
        final boolean anythingPublishable = targets.stream()
                .anyMatch(target -> verdict(target.identifier()) instanceof Publishable);
        if (anythingPublishable) {
            return PolicyReport.empty();
        }
        final List<PolicyFinding> findings = new ArrayList<>();
        reactor.modules().stream()
                .filter(module -> !"true".equals(
                        reactor.effective(module).getProperties().getProperty(DEPLOY_SKIP_PROPERTY)))
                .map(module -> PolicyFinding.inFile(module + "/pom.xml", "publication-boundary",
                        module + " would be published while no target's preconditions are met"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    private boolean supplied(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).anyMatch(Field::supplied);
    }

    private static List<String> reversedParts(String domain) {
        final List<String> parts = new ArrayList<>(List.of(domain.split("\\.")));
        Collections.reverse(parts);
        return parts;
    }
}
