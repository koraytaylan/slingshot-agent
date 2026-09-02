package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/** A component reaching a service the container cannot see. */
@Component(service = Object.class)
public final class ComponentManualLookup {

    /**
     * Reaches a service by hand.
     *
     * @param context something to ask
     * @return what it found
     */
    public String find(org.osgi.framework.BundleContext context) {
        return String.valueOf(context.getService(context.getServiceReference("anything")));
    }
}
