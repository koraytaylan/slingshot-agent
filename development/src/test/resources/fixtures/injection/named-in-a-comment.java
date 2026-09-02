package rs.slingshot.agent.fixture;

/**
 * A class explaining why createQuery and addNode are refused, and calling neither.
 *
 * <p>A search here walks resources through the caller's own resolver, so there is no statement for
 * a value like ' or '1'='1 to break out of.</p>
 */
public final class NamedInAComment {

    /**
     * Finds pages by walking.
     *
     * @param resolver the caller's own
     * @param template what the caller asked for
     * @return what it found
     */
    public Object find(Object resolver, String template) {
        return resolver.getResource(template);
    }
}
