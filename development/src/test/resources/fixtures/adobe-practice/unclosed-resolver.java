package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/** A resolver closed in a trailing block an early return skips. */
public final class UnclosedResolver {

    private UnclosedResolver() {
    }

    /**
     * Reads something, sometimes.
     *
     * @param factory the factory
     * @param path the path
     * @return what it read
     */
    public static String read(ResourceResolverFactory factory, String path) {
        final ResourceResolver resolver = factory.getResourceResolver(null);
        if (path.isEmpty()) {
            return "";
        }
        final String read = String.valueOf(resolver.getResource(path));
        resolver.close();
        return read;
    }
}
