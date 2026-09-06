// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.cm.ConfigurationAdmin;

/** Test-only adapter to the installed product's private console API and real Config Admin. */
public final class OperatorAuthorizationProbe extends SlingAllMethodsServlet
        implements BundleActivator, BundleListener {

    private static final long serialVersionUID = 1L;
    private static final String PRODUCT = "rs.slingshot.agent.core";
    private static final String PID = "rs.slingshot.agent.http.AuthorizationGate";
    private final AtomicInteger transitions = new AtomicInteger();

    @Override
    public void start(BundleContext context) {
        context.addBundleListener(this);
        context.registerService(Servlet.class, this, FrameworkUtil.asDictionary(Map.of(
                "sling.servlet.paths", "/bin/slingshot-proof/authorization",
                "sling.servlet.methods", new String[] { "GET", "POST" })));
    }

    @Override
    public void stop(BundleContext context) {
        context.removeBundleListener(this);
    }

    @Override
    public void bundleChanged(BundleEvent event) {
        if (PRODUCT.equals(event.getBundle().getSymbolicName())) {
            transitions.incrementAndGet();
        }
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        try {
            if ("state".equals(request.getParameter("action"))) {
                response.getWriter().write(product().loadClass(PID).getMethod("permittedGroups")
                        .invoke(null) + "/" + transitions.get());
                return;
            }
            response.getWriter().write(console(request));
        } catch (final ReflectiveOperationException failed) {
            throw new IOException(failed);
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        if (!"admin".equals(request.getRemoteUser())) {
            response.setStatus(403);
            return;
        }
        final String action = request.getParameter("action");
        try {
            if ("prepare".equals(action)) {
                prepare(Objects.requireNonNull(request.getResourceResolver().adaptTo(Session.class)));
            } else {
                configure(action);
            }
            response.getWriter().write("done");
        } catch (final javax.jcr.RepositoryException failed) {
            throw new IOException(failed);
        }
    }

    private static void prepare(Session session) throws javax.jcr.RepositoryException {
        final var users = ((JackrabbitSession) session).getUserManager();
        for (final String suffix : new String[] { "one", "two" }) {
            final var user = users.createUser("proof-operator-" + suffix, "proof-password");
            final var group = users.createGroup("proof-operators-" + suffix);
            group.addMember(user);
        }
        session.save();
    }

    private static void configure(String action) throws IOException {
        final BundleContext context = context();
        final var reference = Objects.requireNonNull(context.getServiceReference(ConfigurationAdmin.class));
        try {
            final var configuration = context.getService(reference).getConfiguration(PID, null);
            if ("delete".equals(action)) {
                configuration.delete();
                return;
            }
            configuration.update(FrameworkUtil.asDictionary(Map.of("permitted.groups",
                    "empty".equals(action) ? new String[0] : new String[] { action })));
        } finally {
            context.ungetService(reference);
        }
    }

    private static String console(SlingHttpServletRequest request) throws ReflectiveOperationException {
        final Bundle product = product();
        final Class<?> identity = product.loadClass("rs.slingshot.agent.http.CallerIdentity");
        final Class<?> servlet = product.loadClass("rs.slingshot.agent.http.SubmitServlet");
        final Class<?> groupsType = product.loadClass(PID + "$Groups");
        final Object groups = servlet.getMethod("groupsOf", Session.class, identity).invoke(null,
                request.getResourceResolver().adaptTo(Session.class),
                identity.getConstructor(String.class).newInstance(request.getRemoteUser()));
        final Class<?> source = product.loadClass("rs.slingshot.agent.console.ConsoleDataSource");
        final Class<?> rows = product.loadClass(source.getName() + "$Rows");
        final Class<?> query = product.loadClass(source.getName() + "$Request");
        final Object readable = product.loadClass(source.getName() + "$Unreadable")
                .getConstructor(String.class).newInstance("authorized read");
        final Object screen = Proxy.newProxyInstance(product.adapt(BundleWiring.class).getClassLoader(),
                new Class<?>[] { rows },
                (proxy, method, arguments) -> readable);
        final Object answer = source.getMethod("answer", query).invoke(
                source.getConstructor(rows).newInstance(screen),
                query.getConstructor(groupsType, long.class, long.class, long.class)
                        .newInstance(groups, 0L, 1L, 1L));
        return answer.getClass().getSimpleName();
    }

    private static Bundle product() {
        return Arrays.stream(context().getBundles())
                .filter(bundle -> PRODUCT.equals(bundle.getSymbolicName())).findFirst().orElseThrow();
    }

    private static BundleContext context() {
        return FrameworkUtil.getBundle(OperatorAuthorizationProbe.class).getBundleContext();
    }
}
