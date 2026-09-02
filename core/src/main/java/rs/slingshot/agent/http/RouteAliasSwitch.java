// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.Servlet;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.route.RouteAlias;

/**
 * The client's old spellings, served only where a deployment has said to serve them.
 *
 * <p>Every alias is a second path to one servlet and never a second implementation, so an alias
 * answers byte for byte what its canonical route answers — including its refusals. What differs is
 * only which path reached it.</p>
 *
 * <p>Off in the shipped configuration, and off is the interesting half. {@code /libs} is a
 * namespace customers treat as static: their dispatcher and their content delivery network are
 * frequently configured to pass it more freely than anything else, because that is where client
 * libraries live. An authenticated state-changing route sitting there is a wider surface than this
 * agent asked for, so a deployment running a client that still needs the old spellings turns them
 * on deliberately, one path at a time, and a deployment whose client has caught up never has them
 * at all.</p>
 *
 * <p>One path at a time rather than all or nothing, because the set has an end: as the client
 * corrects one constant, the deployment drops one row, and what is left is exactly what is still
 * needed rather than everything that ever was.</p>
 */
@Component(service = RouteAliasSwitch.class, immediate = true)
@Designate(ocd = RouteAliasSwitch.Spellings.class)
public final class RouteAliasSwitch {

    /** What an operator names the paths in. */
    @ObjectClassDefinition(name = "Slingshot Agent Route Aliases",
            description = "The client's old route spellings this deployment still serves. Empty is"
                    + " the shipped value and means the canonical routes and nothing else. Each"
                    + " path named here is a second path to one servlet, carried until the client"
                    + " repository corrects the constant that asks for it; see"
                    + " docs/CLIENT_COMPATIBILITY.md for what is pending and why /libs is the wrong"
                    + " destination for a state-changing route.")
    public @interface Spellings {

        /**
         * Which of the declared aliases this deployment serves.
         *
         * @return the paths, which are empty in the shipped configuration
         */
        @AttributeDefinition(name = "Served alias paths",
                description = "Exactly the alias paths this deployment answers on. A path that is"
                        + " not a declared alias serves nothing.")
        String[] served_paths() default { };
    }

    /**
     * The alias paths this bundle is answering on, which is none until a deployment says otherwise.
     *
     * <p>Held here because the request shape has to know: a servlet registered at a path is only
     * half of serving it, and a path this build will answer on is one decision rather than two.</p>
     */
    private static final AtomicReference<List<String>> SERVED =
            new AtomicReference<>(List.of());

    private final AtomicReference<List<ServiceRegistration<Servlet>>> registered =
            new AtomicReference<>(List.of());

    /** Holds a switch that is serving nothing. */
    public RouteAliasSwitch() {
    }

    /**
     * Starts serving exactly the alias paths this deployment named, and no others.
     *
     * @param context this bundle's own context, which is where an alias is registered
     * @param spellings what the deployment named
     */
    @Activate
    public void started(BundleContext context, Spellings spellings) {
        final List<RouteAlias> serving = declared().stream()
                .filter(alias -> List.of(spellings.served_paths()).contains(alias.path()))
                .toList();
        SERVED.set(serving.stream().map(RouteAlias::path).toList());
        final List<ServiceRegistration<Servlet>> registrations = new ArrayList<>();
        serving.forEach(alias -> servletFor(alias.routeName()).ifPresent(servlet ->
                registrations.add(context.registerService(Servlet.class, servlet,
                        FrameworkUtil.asDictionary(
                                Map.of("sling.servlet.paths", alias.path()))))));
        registered.set(registrations);
    }

    /**
     * Stops serving them, and leaves nothing registered behind.
     *
     * <p>A registration that outlived the component would be a path answering out of a bundle
     * nobody can configure any more, which is the shape of a surface somebody cannot turn off.</p>
     */
    @Deactivate
    public void stopped() {
        registered.getAndSet(List.of()).forEach(ServiceRegistration::unregister);
        SERVED.set(List.of());
    }

    /**
     * Whether one path is an alias this deployment serves for one route.
     *
     * @param path the path the request arrived at
     * @param route the route being considered
     * @return whether the path reaches that route
     */
    public static boolean serves(String path, AgentRoute route) {
        return SERVED.get().contains(path)
                && aliasAt(path).filter(alias -> alias.routeName().equals(route.name())).isPresent();
    }

    /**
     * The alias one path is, whether or not this deployment serves it.
     *
     * @param path the path
     * @return the alias, or nothing where the table declares none there
     */
    public static Optional<RouteAlias> aliasAt(String path) {
        return declared().stream().filter(alias -> alias.path().equals(path)).findFirst();
    }

    /**
     * Every alias path this deployment is answering on.
     *
     * @return the paths, which are none in the shipped configuration
     */
    public static List<String> served() {
        return SERVED.get();
    }

    /**
     * Every alias the committed table declares, served or not.
     *
     * @return the aliases, in the table's own order
     */
    public static List<RouteAlias> declared() {
        final AgentRouteTable.Outcome outcome = AgentRouteTable.load();
        return outcome instanceof final AgentRouteTable.Loaded loaded
                ? loaded.table().aliases()
                : List.of();
    }

    /**
     * The servlet one route is answered by, which an alias reaches rather than replaces.
     *
     * @param routeName the route's own name in the committed table
     * @return the servlet, or nothing where this build serves no such route
     */
    public static Optional<Servlet> servletFor(String routeName) {
        return switch (routeName) {
            case CapabilityServlet.ROUTE_NAME -> Optional.of(new CapabilityServlet());
            case SubmitServlet.ROUTE_NAME -> Optional.of(new SubmitServlet());
            case OperationLookupServlet.ROUTE_NAME -> Optional.of(new OperationLookupServlet());
            case PhysicalJobServlet.ROUTE_NAME -> Optional.of(new PhysicalJobServlet());
            case HighWaterServlet.ROUTE_NAME -> Optional.of(new HighWaterServlet());
            case EventStreamServlet.ROUTE_NAME -> Optional.of(new EventStreamServlet());
            case ArtifactServlet.ROUTE_NAME -> Optional.of(new ArtifactServlet());
            default -> Optional.empty();
        };
    }
}
