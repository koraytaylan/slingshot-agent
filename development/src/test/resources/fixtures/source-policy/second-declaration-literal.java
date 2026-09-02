package rs.slingshot.agent.fixture;

/** A file restating a bound the contract already declares. */
public final class SecondDeclarationLiteral {

    /** How large a query string may be. */
    private static final int QUERY_CEILING = 8192;

    private SecondDeclarationLiteral() {
    }

    /**
     * The ceiling this file restated.
     *
     * @return the ceiling
     */
    public static int ceiling() {
        return QUERY_CEILING;
    }
}
