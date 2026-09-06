// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.SlingHttpServletRequest;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionRecord;

/** Durable ownership and current operator membership, kept separate from state-tree visibility. */
public final class StateAuthority {

    private StateAuthority() {
    }

    /**
     * An authenticated caller and membership lookup under that caller's original session.
     *
     * @param caller the platform-established caller
     * @param groups the original caller's group lookup, never the service session's
     */
    public record Viewer(StatePath.Caller caller, AuthorizationGate.Groups groups) {
    }

    /**
     * Captures the original caller independently of internal state-session access.
     *
     * @param request the authenticated request
     * @return the caller and membership source, or absence if identity or session is unavailable
     */
    public static Optional<Viewer> viewer(SlingHttpServletRequest request) {
        final AuthenticationGate.Outcome asking = AuthenticationGate.of(request);
        if (!(asking instanceof final AuthenticationGate.Admitted admitted)) {
            return Optional.empty();
        }
        final Optional<Session> session = Optional.ofNullable(
                request.getResourceResolver().adaptTo(Session.class));
        if (session.isEmpty()) {
            return Optional.empty();
        }
        return admitted.caller().counted().map(caller -> new Viewer(caller,
                SubmitServlet.groupsOf(session.get(), admitted.caller())));
    }

    /**
     * Reads a valid owner from the durable operation record.
     *
     * @param store the internal state session
     * @param operation the operation record
     * @return the stored owner, or absence for missing or malformed ownership
     * @throws RepositoryException if the state cannot be read
     */
    public static Optional<StatePath.Caller> owner(Session store, StatePath operation)
            throws RepositoryException {
        if (!store.nodeExists(operation.path())) {
            return Optional.empty();
        }
        final Node record = store.getNode(operation.path());
        if (!record.hasProperty(OperationStore.CALLER)) {
            return Optional.empty();
        }
        final StatePath.Outcome owner = StatePath.caller(
                record.getProperty(OperationStore.CALLER).getString());
        return owner instanceof final StatePath.Held held ? Optional.of(held.caller()) : Optional.empty();
    }

    /**
     * Authorizes a subscription only when its binding agrees with the durable operation owner.
     *
     * @param store the internal state session
     * @param subscription the complete subscription record
     * @param viewer the original caller and membership source
     * @param route the route whose requirement applies
     * @return whether the binding is valid and the caller owns or may operate the work
     * @throws RepositoryException if state cannot be read
     */
    public static boolean subscription(Session store, SubscriptionRecord subscription, Viewer viewer,
                                        String route) throws RepositoryException {
        final StatePath path = StatePath.operation(subscription.generation(),
                subscription.binding().operation());
        return owner(store, path).filter(subscription.binding().caller()::equals).isPresent()
                && operation(store, path, viewer, route);
    }

    /**
     * Authorizes an operation from its persisted owner and the route's declared requirement.
     *
     * @param store the internal state session
     * @param operation the operation record
     * @param viewer the original caller and membership source
     * @param route the route whose requirement applies
     * @return whether the existing, owned record may be accessed
     * @throws RepositoryException if the state cannot be read
     */
    public static boolean operation(Session store, StatePath operation, Viewer viewer, String route)
            throws RepositoryException {
        final Optional<StatePath.Caller> owner = owner(store, operation);
        if (owner.isEmpty()) {
            return false;
        }
        final AuthorizationGate.Ownership ownership = owner.get().equals(viewer.caller())
                ? AuthorizationGate.Ownership.THE_CALLERS_OWN : AuthorizationGate.Ownership.SOMEBODY_ELSES;
        return AuthorizationGate.of(new AuthorizationGate.Request(route,
                AuthorizationGate.permittedGroups(), viewer.groups(), ownership))
                instanceof AuthorizationGate.Admitted;
    }
}
