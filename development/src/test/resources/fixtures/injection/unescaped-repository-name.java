package rs.slingshot.agent.fixture;

/** A class that writes a caller's value into the repository as a node name. */
public final class UnescapedRepositoryName {

    /**
     * Makes a node.
     *
     * @param parent where
     * @param name what the caller asked it be called
     * @return the node
     */
    public Object make(Object parent, String name) {
        return parent.addNode(name);
    }
}
