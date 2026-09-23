// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.framework;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.BundleState;
import rs.slingshot.agent.command.platform.ComponentState;

/**
 * The framework's own bundles and the component runtime's own components.
 *
 * <p>Read from the framework at the moment of asking, and never held: a bundle listing is the
 * framework's answer now. A transition reaches here only after the deployment's control gate has
 * permitted it, and its answer is the state the framework reports afterwards rather than the state
 * that was asked for.</p>
 */
@Component(service = BundleInventory.class)
public final class DefaultBundleInventory implements BundleInventory {

    private static final String NOT_FOUND = "bundle_not_found";
    private static final String TRANSITION_REFUSED = "bundle_transition_refused";

    /** The framework's own number for itself, which no command stops or refreshes. */
    private static final long SYSTEM_BUNDLE = 0;

    /** The configuration policy under which a component takes no configuration at all. */
    private static final String IGNORED_CONFIGURATION = "ignore";

    /** Reads every bundle the framework holds, afresh on each call. */
    private final Supplier<Bundle[]> installed;
    /** The bundle answering, which no command stops or refreshes. */
    private final long answering;
    /** Reaches the framework's wiring, through which a refresh is asked for. */
    private final Supplier<FrameworkWiring> wiring;
    private final ServiceComponentRuntime components;

    /**
     * Holds the inventory the framework and its component runtime answer.
     *
     * @param context this bundle's own context, through which the framework is read
     * @param components the component runtime
     */
    @Activate
    public DefaultBundleInventory(BundleContext context,
                                  @Reference ServiceComponentRuntime components) {
        this.installed = context::getBundles;
        this.answering = context.getBundle().getBundleId();
        this.wiring = () -> context.getBundle(SYSTEM_BUNDLE).adapt(FrameworkWiring.class);
        this.components = components;
    }

    @Override
    public Outcome bundles(String prefix, List<BundleState> states) {
        return new Bundles(Arrays.stream(installed.get())
                .filter(bundle -> String.valueOf(bundle.getSymbolicName()).startsWith(prefix))
                .map(bundle -> new BundleEntry(bundle.getBundleId(),
                        String.valueOf(bundle.getSymbolicName()), bundle.getVersion().toString(),
                        stateOf(bundle.getState())))
                .filter(entry -> states.isEmpty() || states.contains(entry.state()))
                .toList());
    }

    @Override
    public Outcome components(String prefix, List<ComponentState> states) {
        final List<ComponentEntry> found = new ArrayList<>();
        for (final ComponentDescriptionDTO description : components.getComponentDescriptionDTOs()) {
            if (!String.valueOf(description.name).startsWith(prefix)) {
                continue;
            }
            final ComponentEntry entry = new ComponentEntry(description.name,
                    description.bundle == null ? "" : String.valueOf(description.bundle.symbolicName),
                    serviceOf(description), stateOf(description));
            if (states.isEmpty() || states.contains(entry.state())) {
                found.add(entry);
            }
        }
        return new Components(found);
    }

    @Override
    public Outcome transition(String symbolicName, Transition transition) {
        final Optional<Bundle> named = Arrays.stream(installed.get())
                .filter(bundle -> symbolicName.equals(bundle.getSymbolicName()))
                .max(Comparator.comparing(Bundle::getVersion));
        if (named.isEmpty()) {
            return new Refused(NOT_FOUND, symbolicName + " names no bundle the framework holds");
        }
        final Bundle bundle = named.get();
        if (bundle.getBundleId() == SYSTEM_BUNDLE || bundle.getBundleId() == answering) {
            return new Refused(TRANSITION_REFUSED, symbolicName + " is the framework itself or the"
                    + " bundle answering this command, and neither is stopped or refreshed from"
                    + " inside: doing so ends the request that asked");
        }
        try {
            switch (transition) {
                case START -> bundle.start();
                case STOP -> bundle.stop();
                default -> wiring.get().refreshBundles(List.of(bundle));
            }
        } catch (final BundleException | IllegalStateException | SecurityException refused) {
            return new Refused(TRANSITION_REFUSED, "the framework refused to " + transition.spelling()
                    + " " + symbolicName + ": " + refused.getMessage());
        }
        return new Transitioned(stateOf(bundle.getState()));
    }

    private static String serviceOf(ComponentDescriptionDTO description) {
        if (IGNORED_CONFIGURATION.equals(description.configurationPolicy)
                || description.configurationPid == null
                || description.configurationPid.length == 0) {
            return TAKES_NO_SERVICE;
        }
        return description.configurationPid[0];
    }

    /**
     * The client's word for one component's state.
     *
     * <p>A component switched off is disabled whatever its configurations say. One switched on
     * is the most advanced state any of its configurations has reached, and one with no
     * configuration at all is waiting for the configuration it requires.</p>
     */
    private ComponentState stateOf(ComponentDescriptionDTO description) {
        if (!components.isComponentEnabled(description)) {
            return ComponentState.DISABLED;
        }
        final int most = components.getComponentConfigurationDTOs(description).stream()
                .mapToInt(configuration -> configuration.state)
                .max()
                .orElse(ComponentConfigurationDTO.UNSATISFIED_CONFIGURATION);
        return componentStateOf(most);
    }

    /**
     * The client's word for one of the component runtime's configuration states.
     *
     * @param state the runtime's own state number
     * @return the client's state
     */
    static ComponentState componentStateOf(int state) {
        return switch (state) {
            case ComponentConfigurationDTO.ACTIVE -> ComponentState.ACTIVE;
            case ComponentConfigurationDTO.SATISFIED -> ComponentState.SATISFIED;
            default -> ComponentState.UNSATISFIED;
        };
    }

    /**
     * The client's word for one of the framework's bundle states.
     *
     * @param state the framework's own state number
     * @return the client's state
     */
    static BundleState stateOf(int state) {
        return switch (state) {
            case Bundle.ACTIVE -> BundleState.ACTIVE;
            case Bundle.RESOLVED -> BundleState.RESOLVED;
            case Bundle.STARTING -> BundleState.STARTING;
            case Bundle.STOPPING -> BundleState.STOPPING;
            case Bundle.UNINSTALLED -> BundleState.UNINSTALLED;
            default -> BundleState.INSTALLED;
        };
    }
}
