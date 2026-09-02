package rs.slingshot.agent.fixture;

/** A guard that holds a second thing and offers a second way in. */
public class LeakyProxy {

    private final String guarded;

    private final String store;

    /**
     * Holds one guard.
     *
     * @param guarded what it guards
     * @param store what it also holds
     */
    public LeakyProxy(String guarded, String store) {
        this.guarded = guarded;
        this.store = store;
    }

    /**
     * The way in that decides.
     *
     * @return what it guards
     */
    public String answer() {
        return guarded;
    }

    /**
     * The way around it.
     *
     * @return the store, reachable without asking
     */
    public String store() {
        return store;
    }
}
