package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/** A type reaching for a standing privilege over other people's identities. */
public final class DeprecatedMember {

    private DeprecatedMember() {
    }

    /**
     * Obtains a resolver with power over every identity in the repository.
     *
     * @param factory the factory
     * @return the resolver
     */
    public static ResourceResolver administrative(ResourceResolverFactory factory) {
        return factory.getAdministrativeResourceResolver(null);
    }
}
