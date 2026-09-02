package rs.slingshot.agent.fixture;

/**
 * A file that names every forbidden construct only inside a comment or a string literal.
 *
 * <p>A req is an abbreviation and n is a single character and 8192 is a bound the contract
 * declares, and none of those is declared here.</p>
 */
public final class ForbiddenThingsInCommentsAndStrings {

    /** Text that names the things this repository refuses, as text. */
    public static final String NAMED = "req n 8192 MAXIMUM_ROUTE_QUERY_BYTES";

    private ForbiddenThingsInCommentsAndStrings() {
    }
}
