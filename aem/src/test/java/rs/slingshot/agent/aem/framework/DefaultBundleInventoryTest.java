// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.BundleState;
import rs.slingshot.agent.command.platform.ComponentState;

/**
 * The bundle and component commands' adapter, driven over a framework the suite scripts.
 *
 * <p>The framework and the component runtime are interfaces the platform implements, so what is
 * proved here is the translation: which bundles and components a prefix and a state select, and
 * which configuration a component takes.</p>
 */
final class DefaultBundleInventoryTest {

    @Test
    @DisplayName("bundles are selected by symbolic-name prefix and by state")
    void bundlesAreSelectedByPrefixAndState() {
        final Framework framework = new Framework();
        final List<BundleInventory.BundleEntry> found = assertInstanceOf(
                BundleInventory.Bundles.class, framework.inventory().bundles("com.acme",
                        List.of(BundleState.ACTIVE))).entries();
        assertEquals(List.of("com.acme.core"), found.stream()
                .map(BundleInventory.BundleEntry::symbolicName).toList());
        assertEquals(2, assertInstanceOf(BundleInventory.Bundles.class,
                framework.inventory().bundles("com.acme", List.of())).entries().size(),
                "no state named should select every state");
    }

    @Test
    @DisplayName("a component reads as disabled, as its most advanced configuration, or as waiting")
    void acomponentReadsAsItsState() {
        final Framework framework = new Framework();
        final List<BundleInventory.ComponentEntry> found = assertInstanceOf(
                BundleInventory.Components.class, framework.inventory().components("com.acme",
                        List.of())).entries();
        assertEquals(List.of(
                new BundleInventory.ComponentEntry("com.acme.Active", "com.acme.core",
                        "com.acme.Active", ComponentState.ACTIVE),
                new BundleInventory.ComponentEntry("com.acme.Off", "com.acme.core",
                        BundleInventory.TAKES_NO_SERVICE, ComponentState.DISABLED),
                new BundleInventory.ComponentEntry("com.acme.Waiting", "com.acme.core",
                        BundleInventory.TAKES_NO_SERVICE, ComponentState.UNSATISFIED)), found);
        assertEquals(List.of("com.acme.Active"), assertInstanceOf(
                BundleInventory.Components.class, framework.inventory().components("com.acme",
                        List.of(ComponentState.ACTIVE))).entries().stream()
                .map(BundleInventory.ComponentEntry::name).toList());
    }

    @Test
    @DisplayName("every framework and component runtime state reads as one of the client's")
    void everyStateReadsAsTheClients() {
        assertEquals(BundleState.ACTIVE, DefaultBundleInventory.stateOf(Bundle.ACTIVE));
        assertEquals(BundleState.RESOLVED, DefaultBundleInventory.stateOf(Bundle.RESOLVED));
        assertEquals(BundleState.STARTING, DefaultBundleInventory.stateOf(Bundle.STARTING));
        assertEquals(BundleState.STOPPING, DefaultBundleInventory.stateOf(Bundle.STOPPING));
        assertEquals(BundleState.UNINSTALLED, DefaultBundleInventory.stateOf(Bundle.UNINSTALLED));
        assertEquals(BundleState.INSTALLED, DefaultBundleInventory.stateOf(Bundle.INSTALLED));
        assertEquals(ComponentState.SATISFIED, DefaultBundleInventory.componentStateOf(
                ComponentConfigurationDTO.SATISFIED));
        assertEquals(ComponentState.UNSATISFIED, DefaultBundleInventory.componentStateOf(
                ComponentConfigurationDTO.FAILED_ACTIVATION));
    }

    /** A framework of four bundles and a component runtime of three components. */
    private static final class Framework {

        private final Map<String, Integer> states = new java.util.LinkedHashMap<>();
        private final List<Bundle> bundles = new ArrayList<>();

        Framework() {
            bundles.add(bundle(0, "system.bundle"));
            bundles.add(bundle(1, "com.acme.core"));
            bundles.add(bundle(2, "com.acme.broken"));
            bundles.add(bundle(3, "rs.slingshot.agent.aem"));
            states.put("system.bundle", Bundle.ACTIVE);
            states.put("com.acme.core", Bundle.ACTIVE);
            states.put("com.acme.broken", Bundle.RESOLVED);
            states.put("rs.slingshot.agent.aem", Bundle.ACTIVE);
        }

        DefaultBundleInventory inventory() {
            final BundleContext context = (BundleContext) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {BundleContext.class}, (proxy, method, arguments) -> {
                        if (!"getBundles".equals(method.getName())) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        return bundles.toArray(Bundle[]::new);
                    });
            return new DefaultBundleInventory(context, runtime());
        }

        private Bundle bundle(long identifier, String name) {
            return (Bundle) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {Bundle.class}, (proxy, method, arguments) ->
                            switch (method.getName()) {
                                case "getBundleId" -> identifier;
                                case "getSymbolicName" -> name;
                                case "getVersion" -> Version.parseVersion("1.0.0");
                                case "getState" -> states.get(name);
                                case "equals" -> System.identityHashCode(proxy)
                                        == System.identityHashCode(arguments[0]);
                                case "hashCode" -> (int) identifier;
                                default -> throw new UnsupportedOperationException(
                                        method.getName());
                            });
        }

        private ServiceComponentRuntime runtime() {
            final List<ComponentDescriptionDTO> descriptions = List.of(
                    description("com.acme.Active", "optional", "com.acme.Active"),
                    description("com.acme.Off", "ignore", "com.acme.Off"),
                    description("com.acme.Waiting", "require"),
                    description("org.other.Thing", "optional", "org.other.Thing"));
            return (ServiceComponentRuntime) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {ServiceComponentRuntime.class}, (proxy, method, arguments) ->
                            switch (method.getName()) {
                                case "getComponentDescriptionDTOs" -> descriptions;
                                case "isComponentEnabled" -> !"com.acme.Off".equals(
                                        ((ComponentDescriptionDTO) arguments[0]).name);
                                case "getComponentConfigurationDTOs" -> configurations(
                                        ((ComponentDescriptionDTO) arguments[0]).name);
                                default -> throw new UnsupportedOperationException(
                                        method.getName());
                            });
        }

        private static List<ComponentConfigurationDTO> configurations(String name) {
            if (!"com.acme.Active".equals(name)) {
                return List.of();
            }
            final ComponentConfigurationDTO satisfied = new ComponentConfigurationDTO();
            satisfied.state = ComponentConfigurationDTO.SATISFIED;
            final ComponentConfigurationDTO active = new ComponentConfigurationDTO();
            active.state = ComponentConfigurationDTO.ACTIVE;
            return List.of(satisfied, active);
        }

        private static ComponentDescriptionDTO description(String name, String policy,
                                                           String... pids) {
            final ComponentDescriptionDTO description = new ComponentDescriptionDTO();
            description.name = name;
            description.configurationPolicy = policy;
            description.configurationPid = pids;
            description.bundle = new BundleDTO();
            description.bundle.symbolicName = name.startsWith("com.acme") ? "com.acme.core"
                    : "org.other";
            return description;
        }
    }
}
