package rs.slingshot.agent.fixture;

/** A class that builds a query statement out of a caller's value. */
public final class QueryByConcatenation {

    /**
     * Finds pages.
     *
     * @param session the session
     * @param template what the caller asked for
     * @return what it found
     */
    public Object find(Object session, String template) {
        return session.createQuery("select * from [cq:Page] where template = '" + template + "'");
    }
}
