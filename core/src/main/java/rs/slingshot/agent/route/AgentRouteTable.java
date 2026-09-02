// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.route;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The one place a route path is produced.
 *
 * <p>No servlet in this product writes its own path. The table is committed, embedded in this
 * bundle as a resource, and read here; a second spelling of a path cannot exist because there is
 * nowhere for it to be written. That matters more than it looks: the sibling repository disagrees
 * with itself about three of these paths, and the disagreement is only visible because both sides
 * are written down somewhere.</p>
 *
 * <p>Every route sits under one prefix, and a table declaring one outside it is refused. Adobe
 * reserves {@code /libs}; a path registration there creates no node and collides with nothing
 * today, which is exactly why the collision arrives at somebody else's upgrade instead.</p>
 */
public final class AgentRouteTable {

    /** Where the committed route table is embedded in this bundle. */
    public static final String TABLE_RESOURCE = "/rs/slingshot/agent/route/agent-routes.toml";

    private static final String ROUTE_TABLE = "route";

    private static final String PREFIX_TABLE = "prefix";

    private static final String ALIAS_TABLE = "alias";

    private final String prefix;
    private final SequencedMap<String, AgentRoute> routes;
    private final List<RouteAlias> aliases;

    private AgentRouteTable(String prefix, SequencedMap<String, AgentRoute> routes,
                            List<RouteAlias> aliases) {
        this.prefix = prefix;
        this.routes = routes;
        this.aliases = aliases;
    }

    /** Why a route table was refused. Each cause is distinct because each has a different fix. */
    public enum Failure {
        /** The table is not there to read. */
        UNREADABLE,
        /** The bytes are not a route table at all. */
        UNPARSABLE,
        /** A route sits outside the one prefix every route this agent serves is under. */
        OUTSIDE_THE_PREFIX,
        /** Two routes carry the same name, so one of them is unreachable. */
        DUPLICATE_ROUTE,
        /** A route names no plan that builds it. */
        NO_OWNING_PLAN,
        /** An alias names a route this table does not declare, so it is a path to nothing. */
        ALIAS_OF_NO_ROUTE,
        /** An alias sits inside the prefix, where it would be a second spelling of a served path. */
        ALIAS_INSIDE_THE_PREFIX,
        /** Two aliases carry the same path, so which route it reaches is whichever was read last. */
        DUPLICATE_ALIAS,
        /** An alias states no client version or no correction, so nobody has agreed to remove it. */
        ALIAS_WITH_NO_END
    }

    /** The result of reading the table: the table, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A table every route of which satisfied every rule.
     *
     * @param table the loaded table
     */
    public record Loaded(AgentRouteTable table) implements Outcome {
    }

    /**
     * A read that produced no table.
     *
     * @param failure why the table was refused
     * @param detail what was refused, named so that somebody can fix it
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Reads the table embedded in this bundle.
     *
     * @return the table, or the one reason it was refused
     */
    public static Outcome load() {
        try (InputStream stream = AgentRouteTable.class.getResourceAsStream(TABLE_RESOURCE)) {
            if (stream == null) {
                return new Refused(Failure.UNREADABLE,
                        "the route table is not embedded in this bundle");
            }
            return read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Reads a route table out of its own bytes.
     *
     * @param document the table's text
     * @return the table, or the one reason it was refused
     */
    public static Outcome read(String document) {
        final List<SequencedMap<String, String>> tables = new ArrayList<>();
        SequencedMap<String, String> current = new LinkedHashMap<>();
        String heading = "";
        int number = 0;
        for (final String raw : document.lines().toList()) {
            number++;
            final String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '[') {
                if (line.charAt(line.length() - 1) != ']') {
                    return new Refused(Failure.UNPARSABLE, "line " + number + " is not a heading");
                }
                if (!current.isEmpty()) {
                    current.put(ROUTE_TABLE, heading);
                    tables.add(current);
                }
                current = new LinkedHashMap<>();
                heading = line.replace("[", "").replace("]", "").strip();
                continue;
            }
            final int assignment = line.indexOf('=');
            if (assignment < 1) {
                return new Refused(Failure.UNPARSABLE,
                        "line " + number + " is neither a heading nor an assignment");
            }
            current.put(line.substring(0, assignment).strip(), unquote(line.substring(assignment + 1)));
        }
        if (!current.isEmpty()) {
            current.put(ROUTE_TABLE, heading);
            tables.add(current);
        }
        return bind(tables);
    }

    private static Outcome bind(List<SequencedMap<String, String>> tables) {
        final Optional<SequencedMap<String, String>> prefixTable = tables.stream()
                .filter(table -> PREFIX_TABLE.equals(table.get(ROUTE_TABLE)))
                .findFirst();
        if (prefixTable.isEmpty()) {
            return new Refused(Failure.UNPARSABLE, "the table declares no route prefix");
        }
        final String prefix = prefixTable.get().getOrDefault("path", "");
        final SequencedMap<String, AgentRoute> routes = new LinkedHashMap<>();
        for (final SequencedMap<String, String> table : tables) {
            if (!ROUTE_TABLE.equals(table.get(ROUTE_TABLE))) {
                continue;
            }
            final Optional<Refused> refusal = addRoute(routes, table, prefix);
            if (refusal.isPresent()) {
                return refusal.get();
            }
        }
        if (routes.isEmpty()) {
            return new Refused(Failure.UNPARSABLE, "the table declares no route at all");
        }
        final List<RouteAlias> aliases = new ArrayList<>();
        for (final SequencedMap<String, String> table : tables) {
            if (!ALIAS_TABLE.equals(table.get(ROUTE_TABLE))) {
                continue;
            }
            final Optional<Refused> refusal = addAlias(aliases, table, routes, prefix);
            if (refusal.isPresent()) {
                return refusal.get();
            }
        }
        return new Loaded(new AgentRouteTable(prefix, routes, aliases));
    }

    private static Optional<Refused> addAlias(List<RouteAlias> aliases,
                                              SequencedMap<String, String> table,
                                              SequencedMap<String, AgentRoute> routes,
                                              String prefix) {
        final String path = table.getOrDefault("path", "");
        final String route = table.getOrDefault("canonical", "");
        if (!routes.containsKey(route)) {
            return Optional.of(new Refused(Failure.ALIAS_OF_NO_ROUTE,
                    path + " is a second path to " + route + ", which this table does not declare"));
        }
        if (path.startsWith(prefix)) {
            return Optional.of(new Refused(Failure.ALIAS_INSIDE_THE_PREFIX, path
                    + " is inside " + prefix + ", where it would be a second spelling of a served"
                    + " path rather than a path carried for somebody else"));
        }
        if (aliases.stream().anyMatch(held -> held.path().equals(path))) {
            return Optional.of(new Refused(Failure.DUPLICATE_ALIAS, path));
        }
        try {
            aliases.add(new RouteAlias(path, route, table.getOrDefault("client_version", ""),
                    table.getOrDefault("pending_correction", ""),
                    table.getOrDefault("reason", "")));
        } catch (final IllegalArgumentException incomplete) {
            return Optional.of(new Refused(Failure.ALIAS_WITH_NO_END,
                    path + ": " + incomplete.getMessage()));
        }
        return Optional.empty();
    }

    /**
     * Every second path this table carries, in the order it declares them.
     *
     * @return the aliases
     */
    public List<RouteAlias> aliases() {
        return Collections.unmodifiableList(aliases);
    }

    /**
     * Every alias that is a second path to one route.
     *
     * @param routeName the route's own name
     * @return its aliases, in the table's own order
     */
    public List<RouteAlias> aliasesOf(String routeName) {
        return aliases.stream().filter(alias -> alias.routeName().equals(routeName)).toList();
    }

    private static Optional<Refused> addRoute(SequencedMap<String, AgentRoute> routes,
                                              SequencedMap<String, String> table, String prefix) {
        final String name = table.getOrDefault("name", "");
        final String path = table.getOrDefault("path", "");
        final String owningPlan = table.getOrDefault("owning_plan", "");
        if (!path.startsWith(prefix)) {
            return Optional.of(new Refused(Failure.OUTSIDE_THE_PREFIX,
                    name + " is reached at " + path + ", outside " + prefix));
        }
        if (owningPlan.isBlank()) {
            return Optional.of(new Refused(Failure.NO_OWNING_PLAN, name));
        }
        final AgentRoute route = new AgentRoute(name, path, table.getOrDefault("method", ""),
                table.getOrDefault("media_type", ""),
                "true".equals(table.getOrDefault("body_permitted", "false"))
                        ? AgentRoute.RequestBody.REQUIRED : AgentRoute.RequestBody.REFUSED,
                owningPlan, table.getOrDefault("reason", ""));
        if (routes.put(name, route) != null) {
            return Optional.of(new Refused(Failure.DUPLICATE_ROUTE, name));
        }
        return Optional.empty();
    }

    /**
     * The one prefix every route this agent serves sits under.
     *
     * @return the prefix
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Every route the table declares, in the order it declares them.
     *
     * @return the routes, by name
     */
    public SequencedMap<String, AgentRoute> routes() {
        return new LinkedHashMap<>(routes);
    }

    /**
     * Every route's name, in the table's own order.
     *
     * @return the names
     */
    public List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(routes.keySet()));
    }

    /**
     * One route by name.
     *
     * @param name the route's own name
     * @return the route
     * @throws IllegalArgumentException if the table declares no route by that name, because a
     *     servlet asking for a path that does not exist is a defect rather than an absence
     */
    public AgentRoute route(String name) {
        final AgentRoute route = routes.get(name);
        if (route == null) {
            throw new IllegalArgumentException("the route table declares no route named " + name);
        }
        return route;
    }

    private static String stripComment(String line) {
        final int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static String unquote(String value) {
        final String stripped = value.strip();
        final boolean quoted = stripped.length() > 1 && stripped.charAt(0) == '"'
                && stripped.charAt(stripped.length() - 1) == '"';
        return quoted ? stripped.substring(1, stripped.length() - 1) : stripped;
    }
}
