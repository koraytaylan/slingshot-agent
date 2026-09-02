// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everywhere a value can leave this agent, and everywhere one can be held.
 *
 * <p>Plan 0004 audited the routes and Plan 0008 audited the console. What is left is the places a
 * value leaves that are not responses at all — a log line, a health check message, an event
 * payload, a stored artifact, and a repository property the agent itself wrote — and those are the
 * ones nobody looks at, because looking at them means knowing they exist.</p>
 *
 * <p>The surface is derived from the routes, the registry, the console and the health checks rather
 * than listed, so nothing added later escapes the audit by not being on a list. And the corpus is
 * held to being planted: a kind nothing holds is a kind nothing is ever scanned for, which is a
 * category that reports clean because it was never in the room.</p>
 */
public final class ExposureSurface {

    /** Where the routes are declared. */
    public static final String ROUTES = "policy/agent-routes.toml";

    /** Where one row per command is declared. */
    public static final String REGISTRY = "policy/commands";

    /** Where the corpus of things that must never leave is declared. */
    public static final String CORPUS = "policy/redaction-corpus.toml";

    /** The rule a place a value can leave that nothing scans is reported under. */
    public static final String A_PLACE_NOBODY_SCANS = "a-place-nobody-scans";

    /** The rule a corpus kind nothing holds is reported under. */
    public static final String A_KIND_NOBODY_PLANTS = "a-kind-nobody-plants";

    /**
     * The six places a value leaves, which is more than the two anybody audits by habit.
     *
     * <p>A response body and a header are the two somebody thinks of. The other four leave without
     * anybody watching: a log line goes to a file somebody else reads, an event goes down a stream
     * that is not a response, an artifact is bytes nobody re-reads, and a property the agent wrote
     * sits in the repository until somebody looks.</p>
     *
     * <p>The first four are spelled the way the route audit spells them, because two audits that
     * named one place differently would each believe the other covered it.</p>
     */
    public static final List<String> PLACES =
            List.of("body", "header", "log", "stream", "artifact", "agent-written-property");

    /** Everywhere a planted value is put, so every kind is somewhere the surface passes through. */
    public static final List<String> HOLDERS =
            List.of("the key ring", "the configuration", "request headers", "command arguments",
                    "job properties", "replication transport addresses", "workflow metadata",
                    "repository content");

    /** How a check's own declaration opens, which is where its dashboard name begins. */
    private static final String OPENS_A_NAME = "(\"";

    private ExposureSurface() {
    }

    /**
     * Everything the audit drives, derived rather than listed.
     *
     * @param routes every route the committed table declares
     * @param commands every command the registry declares
     * @param healthChecks every check this agent publishes
     */
    public record Surface(List<String> routes, List<String> commands, List<String> healthChecks) {

        /** Holds a surface nothing can change afterwards. */
        public Surface {
            routes = List.copyOf(routes);
            commands = List.copyOf(commands);
            healthChecks = List.copyOf(healthChecks);
        }

        /**
         * How many things the audit drives, which is what it reports it covered.
         *
         * @return the count
         */
        public int size() {
            return routes.size() + commands.size() + healthChecks.size();
        }
    }

    /**
     * What the audit drives, read out of the committed table, the registry and the checks.
     *
     * @param root the repository root
     * @return the surface
     */
    public static Surface of(Path root) {
        return new Surface(ScenarioInventory.routeNames(root.resolve(ROUTES)),
                EscalationSurface.rowsIn(root).stream()
                        .map(EscalationSurface.Row::wireName).toList(),
                healthCheckNames(root));
    }

    /**
     * Everything the surface and the corpus disagree about.
     *
     * @param root the repository root
     * @return one finding per place nothing scans and per kind nothing holds
     */
    public static PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RedactionAudit.PLACES.stream()
                .filter(place -> !PLACES.contains(place))
                .map(place -> PolicyFinding.inFile(CORPUS, A_PLACE_NOBODY_SCANS,
                        place + " is scanned by the route audit and is not one of the places this"
                                + " audit drives"))
                .forEach(findings::add);
        kindsIn(root).stream()
                .filter(kind -> holderFor(kind).isEmpty())
                .map(kind -> PolicyFinding.inFile(CORPUS, A_KIND_NOBODY_PLANTS,
                        kind + " is a kind nothing here holds, so it is a category that reports"
                                + " clean because it was never in the room"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Where a value of one kind is planted, which is what makes scanning for it mean something.
     *
     * @param kind the corpus kind
     * @return the holder, or the empty string where nothing holds one
     */
    public static String holderFor(String kind) {
        return switch (kind) {
            case "credential" -> "request headers";
            case "token" -> "request headers";
            case "key" -> "the key ring";
            case "repository-path" -> "repository content";
            case "internal-name" -> "command arguments";
            case "queue-or-topic" -> "job properties";
            case "transport-address" -> "replication transport addresses";
            case "configuration-value" -> "the configuration";
            default -> "";
        };
    }

    /**
     * Every kind the corpus declares, read from the corpus rather than restated.
     *
     * @param root the repository root
     * @return the kinds, in the corpus's own order
     */
    public static List<String> kindsIn(Path root) {
        return RedactionAudit.read(root) instanceof final RedactionAudit.Loaded loaded
                ? loaded.audit().corpus().stream().map(RedactionAudit.Secret::kind).toList()
                : List.of();
    }

    /**
     * Every health check this agent publishes, read from its own source rather than a list.
     *
     * @param root the repository root
     * @return the names, in the order the source declares them
     */
    private static List<String> healthCheckNames(Path root) {
        final Path health = root.resolve(
                "core/src/main/java/rs/slingshot/agent/health/AgentHealth.java");
        final List<String> names = new ArrayList<>();
        RepositoryTree.text(health).lines()
                .filter(line -> line.contains("(\"") && line.contains("\", \""))
                .forEach(line -> names.add(line.substring(
                        line.indexOf(OPENS_A_NAME) + OPENS_A_NAME.length(),
                        line.indexOf("\",", line.indexOf(OPENS_A_NAME)))));
        return List.copyOf(names);
    }
}
