package rs.slingshot.agent.fixture;

import org.jetbrains.annotations.NotNull;

/** A type calling an external interface whose contract gives an absent argument a meaning. */
public final class ExternalAbsence {

    /**
     * Makes one user with no password, and passes a null where nothing declares one.
     *
     * @param users the external interface
     * @return what it made
     */
    public @NotNull String make(@NotNull Users users) {
        users.createUser("jane", null);
        users.createUser(null, null);
        return users.rename(null);
    }

    /** The external interface, as the fixture sees it. */
    public interface Users {

        /**
         * Makes a user.
         *
         * @param identifier its name
         * @param password its password, whose absence the interface gives a meaning
         */
        void createUser(@NotNull String identifier, @NotNull String password);

        /**
         * Renames something.
         *
         * @param name the new name
         * @return the name
         */
        @NotNull String rename(@NotNull String name);
    }
}
