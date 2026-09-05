// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/** Inserts one independent writer after preparation and immediately before the real Oak save. */
public final class SaveInterleaving {

    private SaveInterleaving() {
    }

    /** A competing repository action which runs once, before the outer writer saves. */
    @FunctionalInterface
    public interface Action {
        /** Runs the competing writer. */
        void run() throws RepositoryException;
    }

    /** Reproduces Oak's refresh(true) before a node write, after a competing session commits. */
    public static Session beforeWrite(Session session, String path, Action action) {
        final AtomicBoolean inserted = new AtomicBoolean();
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return (Session) Proxy.newProxyInstance(loader, new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    try {
                        final Object returned = method.invoke(session, arguments);
                        if (returned instanceof final Node node && "getNode".equals(method.getName())) {
                            return writing(node, loader, inserted, () -> {
                                action.run();
                                session.refresh(true);
                            }, path);
                        }
                        return returned;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    private static Node writing(Node node, ClassLoader loader, AtomicBoolean inserted,
                                Action action, String path) {
        return (Node) Proxy.newProxyInstance(loader, new Class<?>[] {Node.class},
                (proxy, method, arguments) -> {
                    if ("setProperty".equals(method.getName()) && path.equals(node.getPath())
                            && inserted.compareAndSet(false, true)) {
                        action.run();
                    }
                    try {
                        final Object returned = method.invoke(node, arguments);
                        return returned instanceof final Node child
                                ? writing(child, loader, inserted, action, path) : returned;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    /** Returns a session which preserves real Oak conflict handling at the selected boundary. */
    public static Session before(Session session, Action action) {
        return before(session, action, Thread.currentThread().getContextClassLoader());
    }

    /** Uses the runtime's interface loader when the hosting launcher cannot see JCR. */
    public static Session before(Session session, Action action, ClassLoader loader) {
        final AtomicBoolean inserted = new AtomicBoolean();
        return (Session) Proxy.newProxyInstance(loader,
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    if ("save".equals(method.getName()) && inserted.compareAndSet(false, true)) {
                        action.run();
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }
}
