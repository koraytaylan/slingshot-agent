// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Everything that leaves this agent, scanned for everything that must not.
 *
 * <p>Auditing a route at a time as each one is written is how one gets missed. One corpus scanned
 * against every response, every header, every log line and every piece of a stream is how none
 * does — and a hit names the route and the kind, because "something leaked" is not a thing anybody
 * can act on.</p>
 *
 * <p>Completeness is asserted rather than hoped for. A scan that found nothing because it drove
 * three routes is a scan that proves nothing about the other five, so the audit is told what the
 * table declares and what the mapping declares, and it fails on anything that was never driven.</p>
 */
public final class RedactionAudit {

    /** Where the corpus this audit scans for is committed. */
    public static final String CORPUS_FILE = "policy/redaction-corpus.toml";

    private static final String SECRET_ROWS = "secret";

    /** Every kind of thing that must never leave, and there is no ninth. */
    public static final List<String> KINDS = List.of("credential", "token", "key",
            "repository-path", "internal-name", "queue-or-topic", "transport-address",
            "configuration-value");

    private final List<Secret> corpus;

    private RedactionAudit(List<Secret> corpus) {
        this.corpus = corpus;
    }

    /**
     * One thing that must never leave, and the distinctive value that stands for it.
     *
     * @param kind which of the eight it is
     * @param planted the value planted where this agent could hold one
     * @param heldWhere where this agent could hold one
     */
    public record Secret(String kind, String planted, String heldWhere) {
    }

    /**
     * One thing that left this agent, as it left.
     *
     * @param route which route produced it
     * @param place whether it was a body, a header, a log line, or a piece of a stream
     * @param text what it said
     */
    public record Observation(String route, String place, String text) {
    }

    /** Where an observation came from, and there is no fifth. */
    public static final List<String> PLACES = List.of("body", "header", "log", "stream");

    /** The result of reading the corpus: the audit, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A corpus every kind of which carries a planted value.
     *
     * @param audit the audit
     */
    public record Loaded(RedactionAudit audit) implements Outcome {
    }

    /**
     * A read that produced no audit.
     *
     * @param detail what was refused
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The shape the corpus is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("redaction-corpus")
                .text("corpus.reason")
                .rows(SECRET_ROWS, row -> row.text("kind").text("planted").text("held_where")
                        .text("reason"))
                .build();
    }

    /**
     * Reads the corpus this repository commits.
     *
     * @param root the repository root
     * @return the audit, or the one reason there is none
     */
    public static Outcome read(Path root) {
        return readCorpus(root.resolve(CORPUS_FILE));
    }

    /**
     * Reads a corpus from wherever it sits.
     *
     * @param corpus the corpus document
     * @return the audit, or the one reason there is none
     */
    public static Outcome readCorpus(Path corpus) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(corpus, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final List<Secret> secrets = ((PolicyDocument.Loaded) outcome).document()
                .rows(SECRET_ROWS).stream()
                .map(row -> new Secret(row.text("kind"), row.text("planted"),
                        row.text("held_where")))
                .toList();
        final Optional<Secret> unknown = secrets.stream()
                .filter(secret -> !KINDS.contains(secret.kind()))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(unknown.get().kind()
                    + " is not a kind of thing anybody declared must never leave");
        }
        final Optional<String> unplanted = KINDS.stream()
                .filter(kind -> secrets.stream().noneMatch(secret -> secret.kind().equals(kind)))
                .findFirst();
        if (unplanted.isPresent()) {
            return new Refused(unplanted.get()
                    + " is declared and nothing is planted for it, so no drive exercises it");
        }
        return new Loaded(new RedactionAudit(secrets));
    }

    /**
     * Everything that must never leave, in the corpus's own order.
     *
     * @return the corpus
     */
    public List<Secret> corpus() {
        return Collections.unmodifiableList(corpus);
    }

    /**
     * Whether anything that left carried anything that must not.
     *
     * @param observed everything that left, as it left
     * @return one finding per hit, naming the route, the place, and the kind
     */
    public PolicyReport against(List<Observation> observed) {
        final List<PolicyFinding> findings = new ArrayList<>();
        observed.forEach(observation -> corpus.stream()
                .filter(secret -> observation.text().contains(secret.planted()))
                .map(secret -> PolicyFinding.inFile(CORPUS_FILE, "leaked-" + secret.kind(),
                        observation.route() + " let a " + secret.kind() + " out in its "
                                + observation.place()))
                .forEach(findings::add));
        observed.stream()
                .filter(observation -> !PLACES.contains(observation.place()))
                .map(observation -> PolicyFinding.inFile(CORPUS_FILE, "unknown-place",
                        observation.place() + " is not a place anything leaves from"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether the drive that produced those observations covered everything there is to cover.
     *
     * @param observed everything that left, as it left
     * @param routes every route the table declares
     * @param categories every failure category the mapping declares
     * @return one finding per route nothing drove, per category nothing drove, and per place
     *     nothing was observed from
     */
    public PolicyReport completeness(List<Observation> observed, List<String> routes,
                                     List<String> categories) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<String> driven = observed.stream().map(Observation::route).distinct().toList();
        routes.stream()
                .filter(route -> !driven.contains(route))
                .map(route -> PolicyFinding.inFile(CORPUS_FILE, "route-nothing-drove",
                        route + " is declared and this drive never asked for it, so the scan"
                                + " proves nothing about it"))
                .forEach(findings::add);
        categories.stream()
                .filter(category -> observed.stream()
                        .noneMatch(observation -> observation.text().contains(category)
                                || observation.route().contains(category)))
                .map(category -> PolicyFinding.inFile(CORPUS_FILE, "category-nothing-drove",
                        category + " is a way this agent refuses and this drive never produced it"))
                .forEach(findings::add);
        PLACES.stream()
                .filter(place -> observed.stream()
                        .noneMatch(observation -> observation.place().equals(place)))
                .map(place -> PolicyFinding.inFile(CORPUS_FILE, "place-nothing-observed",
                        "nothing was observed from a " + place + ", and a stream error is the"
                                + " easiest one to forget"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }
}
