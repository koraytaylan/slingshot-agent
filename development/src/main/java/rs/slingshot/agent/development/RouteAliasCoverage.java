// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Whether the aliases this side carries are the ones the client actually asks for.
 *
 * <p>Both directions, because each one hides a different mistake. A client constant nothing serves
 * is a client that cannot talk to this agent at all, found here rather than by somebody watching a
 * daemon fail. An alias no client constant needs is a path in {@code /libs} that outlived the
 * reason it was added — which is how a temporary compatibility surface becomes a permanent one
 * nobody remembers deciding on.</p>
 *
 * <p>The client's constants are evidence rather than recollection: {@code
 * policy/client-route-constants.toml} records the file and the symbol each value was read from, at
 * a named commit, so a row somebody cannot find in that repository fails here.</p>
 */
public final class RouteAliasCoverage {

    /** Where the client's own route constants are recorded. */
    public static final String CONSTANTS_FILE = "policy/client-route-constants.toml";

    /** Where this side's routes and aliases are declared. */
    public static final String ROUTES_FILE = "policy/agent-routes.toml";

    private static final String CONSTANT_ROWS = "constant";

    private static final String ALIAS_ROWS = "alias";

    /** What a constant is declared for: the client's own halves, and no third. */
    public static final List<String> KINDS = List.of("production", "suite", "simulator");

    private final List<ConstantRow> constants;
    private final List<AliasRow> aliases;
    private final List<String> served;

    private RouteAliasCoverage(List<ConstantRow> constants, List<AliasRow> aliases,
                               List<String> served) {
        this.constants = constants;
        this.aliases = aliases;
        this.served = served;
    }

    /**
     * One route constant the client repository declares.
     *
     * @param symbol what the client calls it
     * @param value the path it holds
     * @param file where in the client repository it is declared
     * @param kind which half of the client declares it
     */
    public record ConstantRow(String symbol, String value, String file, String kind) {
    }

    /**
     * One second path this side carries.
     *
     * @param path the path the client asks for
     * @param canonical the route it is a second path to
     * @param clientVersion the client version that asks for it
     * @param pendingCorrection what the client repository has to change for the row to go
     */
    public record AliasRow(String path, String canonical, String clientVersion,
                           String pendingCorrection) {
    }

    /** The result of reading both documents: the correspondence, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * Both documents, read and ready to compare.
     *
     * @param coverage the correspondence
     */
    public record Loaded(RouteAliasCoverage coverage) implements Outcome {
    }

    /**
     * A read that produced nothing to compare.
     *
     * @param detail what was refused
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The shape the recorded constants are held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape constantsShape() {
        return PolicyDocument.Shape.named("client-route-constants")
                .text("client.repository")
                .text("client.commit")
                .text("client.version")
                .text("client.reason")
                .rows(CONSTANT_ROWS, row -> row.text("symbol").text("value").text("file")
                        .text("kind").text("reason"))
                .build();
    }

    /**
     * Reads both documents this repository commits.
     *
     * @param root the repository root
     * @return the correspondence, or the one reason there is none
     */
    public static Outcome read(Path root) {
        return readBoth(root.resolve(CONSTANTS_FILE), root.resolve(ROUTES_FILE),
                root.resolve(SHIPPED_CONFIGURATION));
    }

    /** Where a customer's copy of which aliases are served sits. */
    public static final String SHIPPED_CONFIGURATION =
            "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/"
                    + "rs.slingshot.agent.http.RouteAliasSwitch.cfg.json";

    /**
     * Reads both documents from wherever they sit.
     *
     * @param constants the recorded client constants
     * @param routes the route table
     * @param shipped the configuration a customer receives
     * @return the correspondence, or the one reason there is none
     */
    public static Outcome readBoth(Path constants, Path routes, Path shipped) {
        final PolicyDocument.Outcome read = PolicyDocument.load(constants, constantsShape());
        if (read instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument.Outcome table = PolicyDocument.load(routes, ScenarioInventory.routeShape());
        if (table instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument held = ((PolicyDocument.Loaded) read).document();
        final Optional<String> unknown = held.rows(CONSTANT_ROWS).stream()
                .map(row -> row.text("kind"))
                .filter(kind -> !KINDS.contains(kind))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(unknown.get() + " is not a half of the client anybody declared");
        }
        return new Loaded(new RouteAliasCoverage(
                held.rows(CONSTANT_ROWS).stream()
                        .map(row -> new ConstantRow(row.text("symbol"), row.text("value"),
                                row.text("file"), row.text("kind")))
                        .toList(),
                ((PolicyDocument.Loaded) table).document().rows(ALIAS_ROWS).stream()
                        .map(row -> new AliasRow(row.text("path"), row.text("canonical"),
                                row.text("client_version"), row.text("pending_correction")))
                        .toList(),
                servedIn(RepositoryTree.text(shipped))));
    }

    /**
     * Every constant the client declares, in the order they were recorded.
     *
     * @return the constants
     */
    public List<ConstantRow> constants() {
        return Collections.unmodifiableList(constants);
    }

    /**
     * Every alias this side declares, in the table's own order.
     *
     * @return the aliases
     */
    public List<AliasRow> aliases() {
        return Collections.unmodifiableList(aliases);
    }

    /**
     * Every alias path the shipped configuration serves, which is meant to be none.
     *
     * @return the paths
     */
    public List<String> served() {
        return Collections.unmodifiableList(served);
    }

    /**
     * Whether the aliases and the client's constants correspond, in both directions.
     *
     * @param canonicalPaths every path this side serves canonically
     * @return one finding per client constant nothing reaches, per alias no constant needs, per
     *     alias with no client version or no pending correction, and per alias the shipped
     *     configuration turns on
     */
    public PolicyReport against(List<String> canonicalPaths) {
        final List<PolicyFinding> findings = new ArrayList<>();
        constants.stream()
                .filter(constant -> !canonicalPaths.contains(constant.value()))
                .filter(constant -> aliases.stream()
                        .noneMatch(alias -> alias.path().equals(constant.value())))
                .map(constant -> PolicyFinding.inFile(CONSTANTS_FILE, "unserved-client-constant",
                        constant.symbol() + " asks for " + constant.value()
                                + " and nothing here answers there"))
                .forEach(findings::add);
        aliases.stream()
                .filter(alias -> constants.stream()
                        .noneMatch(constant -> constant.value().equals(alias.path())))
                .map(alias -> PolicyFinding.inFile(ROUTES_FILE, "alias-nobody-asks-for",
                        alias.path() + " is carried and no recorded client constant asks for it"))
                .forEach(findings::add);
        aliases.stream()
                .filter(alias -> alias.clientVersion().isBlank()
                        || alias.pendingCorrection().isBlank())
                .map(alias -> PolicyFinding.inFile(ROUTES_FILE, "alias-with-no-end",
                        alias.path() + " states no client version or no pending correction, so"
                                + " nobody has agreed to remove it"))
                .forEach(findings::add);
        served.stream()
                .map(path -> PolicyFinding.inFile(SHIPPED_CONFIGURATION, "alias-shipped-on",
                        path + " is served by what a customer receives, and /libs is a namespace a"
                                + " dispatcher passes more freely than anything else"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    private static List<String> servedIn(String configuration) {
        final List<String> served = new ArrayList<>();
        final java.util.regex.Matcher paths = java.util.regex.Pattern
                .compile("\"(/[^\"]*)\"").matcher(configuration);
        while (paths.find()) {
            served.add(paths.group(1));
        }
        return served;
    }
}
