// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.cm.ConfigurationAdmin;

/** Test-only bootstrap linking fixtures to the original installed product classes and DS state. */
public final class StateAccessProbe extends SlingAllMethodsServlet implements BundleActivator {

    private static final long serialVersionUID = 1L;
    private final AtomicReference<Class<?>> fixtures = new AtomicReference<>();

    @Override
    public void start(BundleContext context) throws ReflectiveOperationException {
        final Bundle product = Arrays.stream(context.getBundles())
                .filter(bundle -> "rs.slingshot.agent.core".equals(bundle.getSymbolicName()))
                .findFirst().orElseThrow();
        final Class<?> linked = new FixtureLoader(context.getBundle(),
                product.adapt(BundleWiring.class).getClassLoader())
                .loadClass("rs.slingshot.agent.proof.StateAccessFixtures");
        fixtures.set(linked);
        final Servlet submission = (Servlet) linked.getMethod("submission").invoke(null);
        context.registerService(Servlet.class, submission, FrameworkUtil.asDictionary(Map.of(
                "sling.servlet.paths", "/bin/slingshot/agent/submit",
                "sling.servlet.methods", "POST", "service.ranking", 10000)));
        context.registerService(Servlet.class, this, FrameworkUtil.asDictionary(Map.of(
                "sling.servlet.paths", "/bin/slingshot-proof/state-access",
                "sling.servlet.methods", new String[] { "GET", "POST" })));
    }

    @Override
    public void stop(BundleContext context) {
        fixtures.set(null);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        invoke(response, "effect", new Class<?>[0]);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        if (!"admin".equals(request.getRemoteUser())) {
            response.setStatus(403);
            return;
        }
        final String action = request.getParameter("action");
        final Session session = Objects.requireNonNull(request.getResourceResolver().adaptTo(Session.class));
        if ("prepare".equals(action)) {
            invoke(response, "prepare", new Class<?>[] { Session.class }, session);
            configure();
        } else if ("artifact".equals(action)) {
            invoke(response, "artifact", new Class<?>[] { Session.class, String.class }, session,
                    request.getParameter("operation"));
        } else if ("remove".equals(action)) {
            invoke(response, "remove", new Class<?>[] { Session.class }, session);
        } else {
            response.setStatus(400);
        }
    }

    private void invoke(SlingHttpServletResponse response, String method, Class<?>[] types,
                          Object... arguments)
            throws IOException {
        try {
            response.getWriter().write(String.valueOf(fixtures.get().getMethod(method, types)
                    .invoke(null, arguments)));
        } catch (final InvocationTargetException failed) {
            throw new IOException(failed.getCause());
        } catch (final ReflectiveOperationException failed) {
            throw new IOException(failed);
        }
    }

    private static void configure() throws IOException {
        final BundleContext context = FrameworkUtil.getBundle(StateAccessProbe.class).getBundleContext();
        final var reference = Objects.requireNonNull(context.getServiceReference(ConfigurationAdmin.class));
        try {
            context.getService(reference).getConfiguration("rs.slingshot.agent.http.AuthorizationGate", null)
                    .update(FrameworkUtil.asDictionary(Map.of("permitted.groups",
                            new String[] { "proof-state-operators" })));
        } finally {
            context.ungetService(reference);
        }
    }

    /** Defines only fixture classes; every product class resolves from the installed bundle's loader. */
    public static final class FixtureLoader extends ClassLoader {

        private final Bundle source;

        FixtureLoader(Bundle source, ClassLoader product) {
            super(product);
            this.source = source;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("rs.slingshot.agent.proof.StateAccessFixtures")) {
                return source.loadClass(name);
            }
            try (var input = Objects.requireNonNull(StateAccessProbe.class.getResourceAsStream(
                    "/" + name.replace('.', '/') + ".class"))) {
                final byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (final IOException failed) {
                throw new ClassNotFoundException(name, failed);
            }
        }
    }
}
