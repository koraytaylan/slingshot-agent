// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;
import java.util.SequencedMap;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.mutation.PropertyValue;

/**
 * What makes, changes, and removes the users and groups a repository holds.
 *
 * <p>Every call takes the caller's own session, and here that is not a convenience — it is the
 * entire access-control story. Group membership is how every permission in this product is granted,
 * so a caller who could add themselves to a group would have granted themselves everything that
 * group can do. Doing this through their own session means the repository refuses exactly what it
 * would have refused had they done it by hand.</p>
 *
 * <p>No credential crosses this seam in either direction. Making a user does not set a password and
 * changing a profile cannot set one: an agent that could set a password is an agent that can become
 * anybody, and there is no argument for it that survives being written down.</p>
 */
public interface PrincipalDirectory {

    /** Whether an authorizable is a person or a group of them. */
    enum Kind {
        /** Somebody who signs in. */
        USER("user"),
        /** A set of them, which is how every permission here is granted. */
        GROUP("group");

        private final String spelling;

        Kind(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How the wire spells this kind.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The kind one spelling names.
         *
         * @param spelled what was written
         * @return the kind, or nothing where nothing is spelled that way
         */
        public static java.util.Optional<Kind> named(String spelled) {
            return java.util.Arrays.stream(values())
                    .filter(kind -> kind.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Both kinds, spelled as the wire spells them.
         *
         * @return the spellings, in declaration order
         */
        public static List<String> spellings() {
            return java.util.Arrays.stream(values()).map(Kind::spelling).toList();
        }
    }

    /**
     * One authorizable as the directory names it.
     *
     * @param authorizableIdentifier what it is called
     * @param kind whether it is a person or a group
     * @param repositoryPath where it is held
     */
    record Principal(String authorizableIdentifier, Kind kind, String repositoryPath) {
    }

    /**
     * One member of a group.
     *
     * @param principal who or what it is
     * @param membership whether the group holds it directly or through another group
     */
    record Member(Principal principal, Membership membership) {
    }

    /** Whether a group holds a member itself or through another group. */
    enum Membership {
        /** The group holds it. */
        DIRECT,
        /** Another group the group holds does. */
        INDIRECT
    }

    /** What one directory call produced. */
    sealed interface Outcome permits Made, Changed, Members, MembershipSettled, Removed, Refused {
    }

    /**
     * An authorizable that was made.
     *
     * @param principal what it is
     */
    record Made(Principal principal) implements Outcome {
    }

    /**
     * An authorizable that was changed.
     *
     * @param repositoryPath where it is held
     */
    record Changed(String repositoryPath) implements Outcome {
    }

    /**
     * The members a listing found.
     *
     * @param members what it found, in the directory's own order
     */
    record Members(List<Member> members) implements Outcome {

        /** Holds the members apart from whatever produced them. */
        public Members {
            members = List.copyOf(members);
        }
    }

    /**
     * What a membership change did, or did not need to do.
     *
     * <p>Whether the membership was already as asked is reported rather than swallowed. A caller
     * granting a permission needs to know whether they granted it or found it, because those are
     * different sentences in an audit and the second one is often the surprising one.</p>
     *
     * @param settlement whether the group was already as asked, or had to be changed
     */
    record MembershipSettled(Settlement settlement) implements Outcome {
    }

    /** Whether a membership change had anything to do. */
    enum Settlement {
        /** The group was already as asked, so nothing changed. */
        ALREADY_AS_ASKED,
        /** It was not, so the membership was granted or taken away. */
        CHANGED
    }

    /**
     * An authorizable that was removed.
     *
     * @param principal what it was
     */
    record Removed(Principal principal) implements Outcome {
    }

    /**
     * The repository would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said, carrying no credential
     */
    record Refused(String category, String detail) implements Outcome {
    }

    /**
     * Makes a user or a group, with no password and no way to set one.
     *
     * <p>Every method here is named for what it does rather than with a verb the tooling reads as
     * a mutator prefix. A seam whose methods are called {@code create} and {@code delete} is a
     * seam every static analyser treats as a mutable object being handed around, and it is not
     * one — it is a stateless view onto the repository's own user manager.</p>
     *
     * @param request what to make
     * @param session the caller's own session, so the repository refuses what it would have refused
     * @return what was made, or the reason nothing was
     */
    Outcome make(CreationRequest request, ResourceResolver session);

    /**
     * What to make.
     *
     * @param authorizableIdentifier what to call it
     * @param kind whether it is a person or a group
     * @param intermediatePath where under the authorizable tree to put it, or empty for the default
     * @param properties what to record on it, in the order the caller wrote them
     */
    record CreationRequest(String authorizableIdentifier, Kind kind, String intermediatePath,
                           List<NamedValue> properties) {

        /** Holds a request whose properties nothing can change afterwards. */
        public CreationRequest {
            properties = List.copyOf(properties);
        }
    }

    /**
     * One property to record, with its own name.
     *
     * @param name the property's own name
     * @param value what to record
     */
    record NamedValue(String name, PropertyValue value) {
    }

    /**
     * Changes what is recorded about one user.
     *
     * @param authorizableIdentifier which user
     * @param properties what to record, by name
     * @param removedPropertyNames what to take away
     * @param session the caller's own session
     * @return where it is held, or the reason nothing changed
     */
    Outcome applyProfile(String authorizableIdentifier,
                          SequencedMap<String, PropertyValue> properties,
                          java.util.SequencedSet<String> removedPropertyNames,
                          ResourceResolver session);

    /**
     * Turns one account off or on.
     *
     * @param authorizableIdentifier which user
     * @param state whether it may be used
     * @param reason what to record about why, which the repository keeps beside the account
     * @param session the caller's own session
     * @return the state it is in now, or the reason nothing changed
     */
    Outcome applyAccountState(String authorizableIdentifier, AccountState state, String reason,
                              ResourceResolver session);

    /**
     * Removes one user or group.
     *
     * @param authorizableIdentifier which one
     * @param expectedKind what the caller believes it is, which is checked before anything happens
     * @param session the caller's own session
     * @return what was removed, or the reason nothing was
     */
    Outcome erase(String authorizableIdentifier, Kind expectedKind, ResourceResolver session);

    /**
     * Adds one authorizable to a group, or takes it out.
     *
     * @param groupIdentifier which group
     * @param memberIdentifier which authorizable
     * @param change whether to grant the membership or take it away
     * @param session the caller's own session
     * @return whether it was already as asked, or the reason nothing changed
     */
    Outcome applyMembership(String groupIdentifier, String memberIdentifier,
                            MembershipChange change, ResourceResolver session);

    /** Whether a membership is being granted or taken away. */
    enum MembershipChange {
        /** Grant it, with everything the group can do. */
        GRANTED,
        /** Take it away, with everything the group can do. */
        WITHDRAWN
    }

    /**
     * Who is in one group.
     *
     * @param groupIdentifier which group
     * @param reach whether members held through other groups count
     * @param session the caller's own session
     * @return the members, or the reason there are none
     */
    Outcome members(String groupIdentifier, Reach reach, ResourceResolver session);

    /** How far a membership listing looks. */
    enum Reach {
        /** Only what the group holds itself. */
        DIRECT_ONLY,
        /** Everything it holds, including through the groups it holds. */
        INCLUDING_INDIRECT
    }
}
