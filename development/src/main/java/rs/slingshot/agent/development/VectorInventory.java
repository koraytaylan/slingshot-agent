// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every document kind, and the rule that none of them exists without a vector.
 *
 * <p>A vector is what makes a disagreement between two implementations a failing test in whichever
 * one changed, rather than a refused submission in production two weeks later. That only works if a
 * document kind cannot exist without one — so a kind with no accepted vector, a kind with no
 * refused vector, a vector naming a kind nobody declared, and a bound with no vector at its edge
 * are four separate findings, each naming what is missing.</p>
 */
public final class VectorInventory {

    /** The rule every finding here is reported under. */
    public static final String RULE = "protocol-vectors";

    private static final String INVENTORY_FILE = "schemas/agent-protocol-vector-inventory.toml";

    /** Where the vectors themselves are committed. */
    public static final String VECTOR_FILE = "schemas/agent-protocol-vectors.json";

    private static final String KIND_ROWS = "kind";

    /**
     * How one vector's identity and its verdict are read out of the committed file.
     *
     * <p>The input and the expected bytes are deliberately not read here. They are documents, and a
     * document inside a document is escaped — so every pattern here matches only unescaped member
     * names, which are the file's own rather than any vector's content. What the bytes have to
     * produce is proved beside the models that produce them.</p>
     */
    private static final Pattern VECTOR = Pattern.compile(
            "\"id\":\"(?<id>[^\"]*)\",\"kind\":\"(?<kind>[^\"]*)\",\"accepted\":"
                    + "(?<accepted>true|false)");

    /** How the members after the documents are read, from the end of one vector. */
    private static final Pattern TAIL = Pattern.compile(
            "\"note\":\"(?<note>[^\"]*)\",\"bound\":\"(?<bound>[^\"]*)\",\"edge\":"
                    + "\"(?<edge>[^\"]*)\"");

    private final List<KindRow> kinds;

    private VectorInventory(List<KindRow> kinds) {
        this.kinds = kinds;
    }

    /**
     * One document kind this protocol has.
     *
     * @param name the kind's own name
     * @param schema the schema that describes it
     * @param reason what the document is for
     */
    public record KindRow(String name, String schema, String reason) {
    }

    /**
     * One committed vector, as the file states it.
     *
     * @param identifier the vector's own identifier
     * @param kind the document kind it is about
     * @param verdict whether the document it carries is one this build accepts or refuses
     * @param note what it proves
     * @param bound the contract limit it is about, or empty where it is about no bound
     * @param edge {@code at} or {@code past}, where it is about a bound
     */
    public record VectorRow(String identifier, String kind, Verdict verdict, String note,
                            String bound, String edge) {

        /**
         * Whether this vector is one the build accepts.
         *
         * @return whether the verdict is acceptance
         */
        public boolean isAccepted() {
            return verdict == Verdict.ACCEPTED;
        }
    }

    /** What a vector says this build does with the document it carries. */
    public enum Verdict {
        /** The document is one this build reads. */
        ACCEPTED,
        /** The document is one this build refuses. */
        REFUSED
    }

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param inventory the document kinds this protocol has
     * @param vectors the committed vectors
     * @param digests the schema record, which is where the bounds a model reads are named
     */
    public record Sources(Path inventory, Path vectors, Path digests) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(INVENTORY_FILE), root.resolve(VECTOR_FILE),
                    root.resolve("schemas/agent-protocol-digests.toml"));
        }

        /**
         * The same sources with the vectors read from somewhere else.
         *
         * @param elsewhere where the vectors sit instead
         * @return the sources
         */
        public Sources withVectors(Path elsewhere) {
            return new Sources(inventory, elsewhere, digests);
        }
    }

    /** The result of reading the inventory: the inventory, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * An inventory that satisfied its shape completely.
     *
     * @param inventory the loaded inventory
     */
    public record Loaded(VectorInventory inventory) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the inventory is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("agent-protocol-vector-inventory")
                .rows(KIND_ROWS, row -> row.text("name").text("schema").text("reason"))
                .build();
    }

    /**
     * Reads the inventory this repository commits.
     *
     * @param root the repository root
     * @return the inventory, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readInventory(root.resolve(INVENTORY_FILE));
    }

    /**
     * Reads an inventory from wherever it sits.
     *
     * @param inventory the inventory document
     * @return the inventory, or the one reason the document was refused
     */
    public static Outcome readInventory(Path inventory) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(inventory, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        return new Loaded(new VectorInventory(
                ((PolicyDocument.Loaded) outcome).document().rows(KIND_ROWS).stream()
                        .map(row -> new KindRow(row.text("name"), row.text("schema"),
                                row.text("reason")))
                        .toList()));
    }

    /**
     * Every document kind this protocol has.
     *
     * @return the rows, in the inventory's own order
     */
    public List<KindRow> kinds() {
        return java.util.Collections.unmodifiableList(kinds);
    }

    /**
     * Every vector one file commits.
     *
     * @param vectors the vector file
     * @return the rows, in the file's own order
     */
    public static List<VectorRow> vectorsIn(Path vectors) {
        if (!Files.isRegularFile(vectors)) {
            return List.of();
        }
        final String content = contentOf(vectors);
        final List<VectorRow> rows = new ArrayList<>();
        final Matcher found = VECTOR.matcher(content);
        final Matcher tail = TAIL.matcher(content);
        while (found.find() && tail.find(found.end())) {
            rows.add(new VectorRow(found.group("id"), found.group("kind"),
                    "true".equals(found.group("accepted")) ? Verdict.ACCEPTED : Verdict.REFUSED,
                    tail.group("note"), tail.group("bound"), tail.group("edge")));
        }
        return rows;
    }

    /**
     * Everything the kinds, the vectors, and the bounds disagree about.
     *
     * @param sources everywhere this check reads from
     * @return one finding per disagreement, each naming what is missing
     */
    public PolicyReport against(Sources sources) {
        final List<VectorRow> vectors = vectorsIn(sources.vectors());
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(shapeOf(vectors));
        findings.addAll(coverage(vectors));
        findings.addAll(boundCoverage(sources, vectors));
        return PolicyReport.of(findings);
    }

    private List<PolicyFinding> shapeOf(List<VectorRow> vectors) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        vectors.forEach(vector -> {
            if (!seen.add(vector.identifier())) {
                findings.add(PolicyFinding.inFile(VECTOR_FILE, "duplicate-vector",
                        vector.identifier() + " is declared more than once"));
            }
            if (kinds.stream().noneMatch(kind -> kind.name().equals(vector.kind()))) {
                findings.add(PolicyFinding.inFile(VECTOR_FILE, "unknown-kind",
                        vector.kind() + " is not a document kind the inventory declares"));
            }
            if (vector.note().isBlank()) {
                findings.add(PolicyFinding.inFile(VECTOR_FILE, "vector-with-no-note",
                        vector.identifier() + " says nothing about what it proves"));
            }
        });
        return findings;
    }

    private List<PolicyFinding> coverage(List<VectorRow> vectors) {
        final List<PolicyFinding> findings = new ArrayList<>();
        kinds.forEach(kind -> {
            if (vectors.stream().noneMatch(vector -> vector.kind().equals(kind.name())
                    && vector.isAccepted())) {
                findings.add(PolicyFinding.inFile(INVENTORY_FILE, "kind-without-a-vector",
                        kind.name() + " has no vector this build accepts"));
            }
            if (vectors.stream().noneMatch(vector -> vector.kind().equals(kind.name())
                    && !vector.isAccepted())) {
                findings.add(PolicyFinding.inFile(INVENTORY_FILE, "kind-without-a-vector",
                        kind.name() + " has no vector this build refuses"));
            }
        });
        return findings;
    }

    private static List<PolicyFinding> boundCoverage(Sources sources, List<VectorRow> vectors) {
        final List<PolicyFinding> findings = new ArrayList<>();
        declaredBounds(sources.digests()).forEach(bound -> List.of("at", "past").forEach(edge -> {
            if (vectors.stream().noneMatch(vector -> vector.bound().equals(bound)
                    && vector.edge().equals(edge))) {
                findings.add(PolicyFinding.inFile(VECTOR_FILE, "bound-without-a-vector",
                        bound + " has no vector " + edge + " it"));
            }
        }));
        return findings;
    }

    /**
     * Every bound a typed model reads, as the schema record names them.
     *
     * @param digests the schema record
     * @return the limit names, in the record's own order
     */
    public static Set<String> declaredBounds(Path digests) {
        if (!Files.isRegularFile(digests)) {
            return Set.of();
        }
        final Set<String> bounds = new LinkedHashSet<>();
        final Matcher named = Pattern.compile("limit = \"(?<limit>[a-z_]+)\"")
                .matcher(contentOf(digests));
        while (named.find()) {
            bounds.add(named.group("limit"));
        }
        return bounds;
    }

    private static String contentOf(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
