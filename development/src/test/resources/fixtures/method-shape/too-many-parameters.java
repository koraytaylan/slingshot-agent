package rs.slingshot.agent.fixture;

import java.util.List;

/** A method with more arguments than a call site can carry. */
public final class TooManyParameters {

    private TooManyParameters() {
    }

    /**
     * Joins everything it was given.
     *
     * @param argument0 one argument
     * @param argument1 one argument
     * @param argument2 one argument
     * @param argument3 one argument
     * @param argument4 one argument
     * @param argument5 one argument
     * @param argument6 one argument
     * @param argument7 one argument
     * @return the joined text
     */
    public static String join(String argument0, String argument1, String argument2, String argument3, String argument4, String argument5, String argument6, String argument7) {
        return String.join("", argument0, argument1, argument2, argument3, argument4, argument5, argument6, argument7);
    }
}
