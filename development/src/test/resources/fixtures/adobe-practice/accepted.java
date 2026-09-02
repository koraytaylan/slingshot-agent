package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/**
 * A type exercising the permitted form of each practice.
 *
 * <p>It names getAdministrativeResourceResolver and loginAdministrative in this sentence and in the
 * text below, and calls neither.</p>
 */
public final class Accepted {

    /** The calls this repository refuses, named here as text rather than made. */
    public static final String REFUSED = "getAdministrativeResourceResolver loginAdministrative";

    private Accepted() {
    }

    /**
     * Reads something through a resolver the language closes on every path.
     *
     * @param factory the factory
     * @param path the path
     * @return what it read
     */
    public static String read(ResourceResolverFactory factory, String path) {
        try (ResourceResolver resolver = factory.getServiceResourceResolver(null)) {
            return String.valueOf(resolver.getResource(path));
        }
    }
}
