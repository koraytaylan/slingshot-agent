// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * Every route driven on a real instance, and everything it said scanned for everything it must not.
 *
 * <p>Auditing a route at a time as each one is written is how one gets missed. This drives every
 * route the committed table declares, with a planted value of every kind the corpus names in the
 * places a caller can put one, and scans every body, every header and every line the instance
 * wrote.</p>
 *
 * <p>The log is half the point. A response goes to one caller; a log line goes to an operator's
 * console, a support bundle, and whatever ships logs off the instance — so a secret that only ever
 * reaches the log is a secret that reaches more people, not fewer.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RedactionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** Where the corpus this drive is scanned against is committed. */
    private static final String CORPUS = "policy/redaction-corpus.toml";

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        // Its own runtime, because this scenario reads the instance's whole log and asks that
        // nothing in it names an internal. A shared instance has served every other scenario by
        // then, and one stack trace from any of them carries a name this refuses - so what it
        // would be reading is the suite's history rather than what this product writes.
        SharedPublicSlingTier.release();
        final InteropTier.Outcome outcome =
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "this scenario left its own container running");
    }

    @Test
    @DisplayName("nothing any route says carries anything the corpus says must never leave")
    void nothinganyRouteSaysCarriesAsecret() {
        final SequencedMap<String, String> said = drive();
        final Map<String, String> planted = planted();
        said.forEach((where, text) -> planted.forEach((kind, value) -> assertFalse(
                text.contains(value),
                where + " let a " + kind + " out: the answer carried " + value)));
    }

    @Test
    @DisplayName("nothing the instance wrote to its own log carries one either")
    void nothingTheLogCarriesAsecret() {
        drive();
        final String written = whatThisProductWrote(tier.capturedOutput());
        assertFalse(written.isEmpty(),
                "the instance wrote nothing at all, so this scan proves nothing");
        planted().forEach((kind, value) -> assertFalse(written.contains(value),
                "the instance's own log carried a " + kind + ": " + value));
    }

    @Test
    @DisplayName("every route the table declares was driven, so the scan covers all of them")
    void everyrouteTheTableDeclaresWasDriven() {
        final SequencedMap<String, String> said = drive();
        routes().forEach(route -> assertTrue(
                said.keySet().stream().anyMatch(where -> where.startsWith(route.name())),
                route.name() + " was never driven, so the scan proves nothing about it"));
        assertTrue(said.keySet().stream().anyMatch(where -> where.endsWith("header")),
                "no header was scanned, and a header is where a token is echoed");
        assertTrue(said.keySet().stream().anyMatch(where -> where.endsWith("stream")),
                "the stream route was never asked for, and a mid-stream error is the easiest one"
                        + " to forget");
    }

    @Test
    @DisplayName("no answer names where this agent keeps things or what it is built out of")
    void noanswerNamesWhereThingsAreKept() {
        final SequencedMap<String, String> said = drive();
        said.forEach((where, text) -> {
            assertFalse(text.contains("/var/slingshot-agent"),
                    where + " named where this agent keeps things: " + text);
            assertFalse(text.contains("rs.slingshot.agent"),
                    where + " named what this agent is built out of: " + text);
        });
    }

    /**
     * Asks every route for something, with a planted value everywhere a caller can put one.
     *
     * @return what came back, by route and place
     */
    private SequencedMap<String, String> drive() {
        final SequencedMap<String, String> said = new LinkedHashMap<>();
        final Map<String, String> planted = planted();
        for (final AgentRoute route : routes()) {
            final HttpResponse<String> answered = ask(route, planted);
            said.put(route.name() + ":body", String.valueOf(answered.body()));
            said.put(route.name() + ":header", answered.headers().map().toString());
        }
        final HttpResponse<String> streamed = requests.readAsAuthenticatedUser(
                tier.address() + "/bin/slingshot/agent/events?daemon_subscription_identifier="
                        + encoded(planted.get("token")) + "&agent_operation_identifier="
                        + AN_IDENTIFIER);
        said.put("events:stream", String.valueOf(streamed.body()));
        return said;
    }

    private HttpResponse<String> ask(AgentRoute route, Map<String, String> planted) {
        final String asked = tier.address() + route.path()
                + "?agent_operation_identifier=" + AN_IDENTIFIER
                + "&artifact_slot=" + encoded(planted.get("key"))
                + "&daemon_subscription_identifier=" + encoded(planted.get("token"));
        return "POST".equals(route.method())
                ? requests.postAsAuthenticatedUser(asked,
                        "{\"" + planted.get("configuration-value") + "\":\""
                                + planted.get("credential") + "\"}", route.mediaType())
                : requests.readAsAuthenticatedUser(asked);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<AgentRoute> routes() {
        final AgentRouteTable table = assertInstanceOf(AgentRouteTable.Loaded.class,
                AgentRouteTable.load(), "the route table was refused").table();
        return table.names().stream().map(table::route).toList();
    }

    private static Map<String, String> planted() {
        final TomlParseResult corpus = parse(REPOSITORY.resolve(CORPUS));
        final TomlArray rows = java.util.Objects.requireNonNull(corpus.getArray("secret"),
                CORPUS + " declares no secret at all, so this drive would scan for nothing");
        final SequencedMap<String, String> planted = new LinkedHashMap<>();
        java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> java.util.Objects.requireNonNull(rows.getTable(index),
                        CORPUS + " holds a row that is not a secret at all"))
                .forEach(secret -> planted.put(String.valueOf(secret.getString("kind")),
                        String.valueOf(secret.getString("planted"))));
        return planted;
    }

    private static TomlParseResult parse(Path document) {
        try {
            final TomlParseResult parsed = Toml.parse(document);
            assertTrue(parsed.errors().isEmpty(), document + " does not parse: " + parsed.errors());
            return parsed;
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(document + " is not readable", unreadable);
        }
    }

    /**
     * What this product wrote, without the framework's own account of its service registry.
     *
     * <p>The platform names every bundle whose services it registers, this one included, at info
     * level and on its own account - {@code Events.Service.rs.slingshot.agent.core} is the
     * framework describing its registry rather than this product disclosing anything. It cannot be
     * prevented without silencing the platform, and it is not what this check is for: what a caller
     * can actually see is held to the same corpus by the three checks about answers.</p>
     *
     * @param written everything the instance wrote
     * @return the part of it this product is answerable for
     */
    private static String whatThisProductWrote(String written) {
        return written.lines()
                .filter(line -> !line.contains("[FelixLogListener] Events."))
                .collect(Collectors.joining("\n"));
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    // Neither of the archives a release also builds: a javadoc jar handed to the
                    // platform as a bundle is refused with a 500 that reads like the product
                    // failing to install.
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources")
                            && !String.valueOf(file.getFileName()).contains("javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
