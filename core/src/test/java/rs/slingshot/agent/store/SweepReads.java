// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

/** Counts real iterator advances without replacing repository behavior. */
final class SweepReads {

    private SweepReads() {
    }

    /** Observes the operation children read from the selected bucket. */
    static Session count(Session session, String bucket, AtomicInteger reads) {
        return wrapped(session, Session.class, bucket, reads);
    }

    private static <T> T wrapped(T target, Class<T> type, String bucket, AtomicInteger reads) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type}, (proxy, method, arguments) -> {
                    try {
                        if (target instanceof NodeIterator
                                && ("nextNode".equals(method.getName()) || "next".equals(method.getName()))) {
                            reads.incrementAndGet();
                        }
                        if (target instanceof NodeIterator && "skip".equals(method.getName())) {
                            reads.addAndGet(Math.toIntExact((Long) arguments[0]));
                        }
                        final Object value = method.invoke(target, arguments);
                        if (value instanceof final Node node) {
                            return wrapped(node, Node.class, bucket, reads);
                        }
                        if (value instanceof final NodeIterator iterator
                                && target instanceof final Node parent && bucket.equals(parent.getPath())) {
                            return wrapped(iterator, NodeIterator.class, bucket, reads);
                        }
                        return value;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                }));
    }
}
