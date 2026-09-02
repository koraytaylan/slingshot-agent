package rs.slingshot.agent.fixture;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;

/** A component holding state every caller shares. */
@Component(service = Object.class)
public final class ComponentMutableState {

    private String lastPath = "";

    /**
     * Remembers the last path anybody asked about.
     *
     * @param path the path
     */
    public void remember(String path) {
        this.lastPath = path;
    }

    /**
     * Answers the last path anybody asked about.
     *
     * @return the path
     */
    public String remembered() {
        return lastPath;
    }
}
