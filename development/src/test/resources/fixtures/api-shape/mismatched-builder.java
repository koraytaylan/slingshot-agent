package rs.slingshot.agent.fixture;

/** A builder whose built type can still be changed afterwards. */
public final class MutableBuilder {

    /**
     * Closes the thing being built.
     *
     * @return the built thing
     */
    public HalfBuilt build() {
        return new HalfBuilt();
    }
}
