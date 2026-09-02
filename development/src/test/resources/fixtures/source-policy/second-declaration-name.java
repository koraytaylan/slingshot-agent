package rs.slingshot.agent.fixture;

/** A file naming a constant after a bound the contract already declares. */
public final class SecondDeclarationName {

    /** A constant named after a bound that lives in the contract. */
    private static final String MAXIMUM_ROUTE_QUERY_BYTES = "read this from the contract instead";

    private SecondDeclarationName() {
    }

    /**
     * The constant this file should not have.
     *
     * @return the constant
     */
    public static String ceiling() {
        return MAXIMUM_ROUTE_QUERY_BYTES;
    }
}
