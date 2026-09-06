// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
final class StateLifecycleServiceTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void activationRefusesUntilTheMaintenanceIdentityIsBound() {
        final StateLifecycleService service = new StateLifecycleService();
        service.activate();
        assertEquals(StateLifecycleService.Availability.UNAVAILABLE,
                StateLifecycleService.observed().availability());
        service.deactivate();
        assertEquals("state lifecycle has stopped", StateLifecycleService.observed().detail());
    }

    @Test
    void boundButUnavailableMaintenanceIdentityLeavesServiceUnavailable() {
        final StateLifecycleService service = new StateLifecycleService();
        service.available(new rs.slingshot.agent.repository.AgentSession(subservice -> {
            throw new LoginException("not ready");
        }));
        service.activate();
        assertEquals(StateLifecycleService.Availability.UNAVAILABLE,
                StateLifecycleService.observed().availability());
        service.deactivate();
    }

    @Test
    void boundOakMaintenancePassPublishesItsDurableObservation() {
        final ResourceResolver shared = sling.resourceResolver();
        final Session session = shared.adaptTo(Session.class);
        try {
            session.getRootNode().addNode("var").addNode("slingshot-agent");
            session.save();
        } catch (final RepositoryException prepared) {
            throw new AssertionError("the lifecycle tree could not be prepared", prepared);
        }
        try (ResourceResolver isolated = (ResourceResolver) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {ResourceResolver.class}, (proxy, method, arguments) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(shared, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                })) {
            final StateLifecycleService service = new StateLifecycleService();
            service.available(new rs.slingshot.agent.repository.AgentSession(
                    subservice -> isolated));
            service.activate();
            assertEquals(StateLifecycleService.Availability.READY,
                    StateLifecycleService.observed().availability(),
                    StateLifecycleService.observed().detail());
            service.deactivate();
        }
    }

    @Test
    void snapshotAndRunValuesRemainObservable() {
        final StateLifecycleService.Snapshot snapshot = new StateLifecycleService.Snapshot(
                StateLifecycleService.Availability.READY, 4, "ok");
        assertEquals(StateLifecycleService.Availability.READY, snapshot.availability());
        assertEquals(4, snapshot.generation());
        assertEquals("ok", snapshot.detail());
        final StateLifecycleService.Run run = new StateLifecycleService.Run(null, "detail");
        assertEquals("detail", run.detail());
    }
}
