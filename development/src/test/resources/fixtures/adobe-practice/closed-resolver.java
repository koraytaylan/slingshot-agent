package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/** A resolver the language closes on every path. */
public final class ClosedResolver {

    private ClosedResolver() {
    }

    /**
     * Reads something, sometimes.
     *
     * @param factory the factory
     * @param path the path
     * @return what it read
     */
    public static String read(ResourceResolverFactory factory, String path) {
        try (ResourceResolver resolver = factory.getResourceResolver(null)) {
            if (path.isEmpty()) {
                return "";
            }
            return String.valueOf(resolver.getResource(path));
        }
    }
}
