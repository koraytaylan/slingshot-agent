package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/** A component serialising every caller through one instance. */
@Component(service = Object.class)
public final class ComponentSynchronisation {

    /**
     * Does something, one caller at a time.
     *
     * @param path the path
     * @return the path
     */
    public String act(String path) {
        synchronized (this) {
            return path;
        }
    }
}
