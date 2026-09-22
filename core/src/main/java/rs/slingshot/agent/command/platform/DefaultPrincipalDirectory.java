// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import javax.jcr.AccessDeniedException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;

/**
 * The repository's own user manager, reached through the caller's own session.
 *
 * <p>Nothing here holds a session of its own. Every call adapts the resolver it was handed, so
 * the repository decides what this caller may do with users and groups exactly as it would have
 * decided had they done it by hand, and a caller who may not add themselves to a group is refused
 * here by the repository rather than by a rule this agent wrote.</p>
 *
 * <p>Every change is committed before its answer is given, and a change that could not be
 * committed is taken back out of the session rather than left for whatever the session does
 * next. Each refusal is spelled in the category the command it answers declares, because a
 * category the client does not know for that command is one it cannot read.</p>
 */
public final class DefaultPrincipalDirectory implements PrincipalDirectory {

    /**
     * The categories one command declares for a failed commit and an absent authorizable.
     *
     * @param commitFailed what a change the repository did not commit is reported as
     * @param notFound what an authorizable nobody can see is reported as
     */
    private record Vocabulary(String commitFailed, String notFound) {
    }

    /** What a creation, a profile change, and a removal report a failed commit as. */
    private static final Vocabulary WRITES = new Vocabulary(
            "repository_commit_failed", "authorizable_not_found");

    /** What an account change reports a failed commit as, which its row declares differently. */
    private static final Vocabulary CONTROLS = new Vocabulary(
            "platform_control_rejected", "authorizable_not_found");

    /** What a membership change and a membership listing report an absent group as. */
    private static final Vocabulary MEMBERSHIPS = new Vocabulary(
            "repository_commit_failed", "group_not_found");

    private static final String ACCESS_DENIED = "authorizable_access_denied";
    private static final String KIND_MISMATCH = "authorizable_kind_mismatch";
    private static final String ALREADY_EXISTS = "authorizable_already_exists";
    private static final String IDENTIFIER_REJECTED = "identifier_rejected";
    private static final String PLACE_REJECTED = "intermediate_path_rejected";
    private static final String PROPERTY_REJECTED = "property_rejected";
    private static final String PROPERTY_NOT_REMOVABLE = "property_not_removable";
    private static final String GROUP_HAS_MEMBERS = "group_has_members";
    private static final String MEMBER_NOT_FOUND = "member_not_found";
    private static final String CYCLE_REFUSED = "membership_cycle_refused";

    /** Opens one directory; it keeps nothing between calls, so every run may have its own. */
    public DefaultPrincipalDirectory() {
        // Stateless: the caller's session is the only state, and it arrives with each call.
    }

    /**
     * The factory a handler holds, which hands each run a directory of its own.
     *
     * @return the factory
     */
    public static PrincipalDirectories directories() {
        return DefaultPrincipalDirectory::new;
    }

    @Override
    public Outcome make(CreationRequest request, ResourceResolver session) {
        return attempt(session, WRITES, request.authorizableIdentifier(), access -> {
            if (access.users().getAuthorizable(request.authorizableIdentifier()) != null) {
                return new Refused(ALREADY_EXISTS, request.authorizableIdentifier()
                        + " already names a user or a group, so nothing was made");
            }
            final Optional<Authorizable> made = created(access.users(), request);
            if (made.isEmpty()) {
                return request.intermediatePath().isEmpty()
                        ? new Refused(IDENTIFIER_REJECTED, request.authorizableIdentifier()
                                + " is not an identifier the repository accepts")
                        : new Refused(PLACE_REJECTED, request.intermediatePath()
                                + " is not a place the repository puts authorizables");
            }
            return written(made.get(), request.properties(), access.values())
                    .<Outcome>map(refused -> refused)
                    .orElseGet(() -> committedAs(access, made.get(), WRITES));
        });
    }

    @Override
    public Outcome applyProfile(String authorizableIdentifier,
                                SequencedMap<String, PropertyValue> properties,
                                SequencedSet<String> removedPropertyNames,
                                ResourceResolver session) {
        return attempt(session, WRITES, authorizableIdentifier, access -> {
            final Optional<Authorizable> found = user(access.users(), authorizableIdentifier);
            if (found.isEmpty()) {
                return absentOrGroup(access.users(), authorizableIdentifier, WRITES);
            }
            final List<NamedValue> named = properties.entrySet().stream()
                    .map(entry -> new NamedValue(entry.getKey(), entry.getValue()))
                    .toList();
            final Optional<Refused> unwritten = written(found.get(), named, access.values());
            if (unwritten.isPresent()) {
                return unwritten.get();
            }
            for (final String removed : removedPropertyNames) {
                if (!found.get().removeProperty(removed)) {
                    return new Refused(PROPERTY_NOT_REMOVABLE, removed + " is not a property "
                            + authorizableIdentifier + " holds, so nothing was changed");
                }
            }
            return access.committed(new Changed(found.get().getPath()), WRITES);
        });
    }

    @Override
    public Outcome applyAccountState(String authorizableIdentifier, AccountState state,
                                     String reason, ResourceResolver session) {
        return attempt(session, CONTROLS, authorizableIdentifier, access -> {
            final Optional<Authorizable> found = user(access.users(), authorizableIdentifier);
            if (found.isEmpty()) {
                return absentOrGroup(access.users(), authorizableIdentifier, CONTROLS);
            }
            final User user = (User) found.get();
            if (user.isAdmin() || user.isSystemUser()) {
                return new Refused(CONTROLS.commitFailed(), authorizableIdentifier + " is the"
                        + " administrator or a system user, and neither is turned off by a"
                        + " command: doing so would lock the platform out of itself");
            }
            if (state == AccountState.DISABLED) {
                user.disable(reasonFor(reason));
            } else {
                // The user manager spells "enabled" as disabling with no reason at all.
                // policy/nullability.toml declares this position.
                user.disable(null);
            }
            return access.committed(new Changed(user.getPath()), CONTROLS);
        });
    }

    @Override
    public Outcome erase(String authorizableIdentifier, Kind expectedKind,
                         ResourceResolver session) {
        return attempt(session, WRITES, authorizableIdentifier, access -> {
            final Authorizable found = access.users().getAuthorizable(authorizableIdentifier);
            if (found == null) {
                return new Refused(WRITES.notFound(), authorizableIdentifier
                        + " names no user or group this caller can see");
            }
            final Kind actual = found.isGroup() ? Kind.GROUP : Kind.USER;
            if (actual != expectedKind) {
                return new Refused(KIND_MISMATCH, authorizableIdentifier + " is a "
                        + actual.spelling() + ", and the request expected a "
                        + expectedKind.spelling() + ", so nothing was removed");
            }
            if (found.isGroup() && ((Group) found).getDeclaredMembers().hasNext()) {
                return new Refused(GROUP_HAS_MEMBERS, authorizableIdentifier + " still has"
                        + " members. It is refused rather than removed with them, because"
                        + " removing it takes away what it grants from every one of them.");
            }
            final PrincipalDirectory.Principal removed = principalOf(found);
            found.remove();
            return access.committed(new Removed(removed), WRITES);
        });
    }

    @Override
    public Outcome applyMembership(String groupIdentifier, String memberIdentifier,
                                   MembershipChange change, ResourceResolver session) {
        return attempt(session, MEMBERSHIPS, groupIdentifier, access -> {
            final Optional<Group> group = group(access.users(), groupIdentifier);
            if (group.isEmpty()) {
                return absentOrUser(access.users(), groupIdentifier);
            }
            final Authorizable member = access.users().getAuthorizable(memberIdentifier);
            if (member == null) {
                return new Refused(MEMBER_NOT_FOUND, memberIdentifier
                        + " names no user or group this caller can see");
            }
            return settled(access, group.get(), member, change);
        });
    }

    @Override
    public Outcome members(String groupIdentifier, Reach reachOfListing,
                           ResourceResolver session) {
        return attempt(session, MEMBERSHIPS, groupIdentifier, access -> {
            final Optional<Group> group = group(access.users(), groupIdentifier);
            if (group.isEmpty()) {
                return absentOrUser(access.users(), groupIdentifier);
            }
            final List<Member> found = new ArrayList<>();
            final Iterator<Authorizable> listed = reachOfListing == Reach.DIRECT_ONLY
                    ? group.get().getDeclaredMembers() : group.get().getMembers();
            while (listed.hasNext()) {
                final Authorizable member = listed.next();
                found.add(new Member(principalOf(member), group.get().isDeclaredMember(member)
                        ? Membership.DIRECT : Membership.INDIRECT));
            }
            return new Members(found);
        });
    }

    /** One call's work against the caller's own user manager. */
    @FunctionalInterface
    private interface Work {

        /**
         * Does the work, committing what it changed before answering.
         *
         * @param access the caller's own user manager and session
         * @return what it did, or the reason it did nothing
         * @throws RepositoryException where the repository refused part of it
         */
        Outcome done(Access access) throws RepositoryException;
    }

    /**
     * Runs one call's work through the caller's own session, and takes back whatever it changed
     * and did not commit.
     *
     * <p>Every refusal leaves the session as it found it, which is what makes a refused call one
     * that changed nothing rather than one that left half a change for the next thing the same
     * request does.</p>
     */
    private static Outcome attempt(ResourceResolver session, Vocabulary vocabulary,
                                   String identifier, Work work) {
        final Optional<Access> reached = reach(session);
        if (reached.isEmpty()) {
            return noSession(vocabulary);
        }
        final Access access = reached.get();
        try {
            final Outcome outcome = work.done(access);
            if (outcome instanceof Refused) {
                access.discard();
            }
            return outcome;
        } catch (final AccessDeniedException denied) {
            access.discard();
            return accessDenied(identifier);
        } catch (final RepositoryException failed) {
            access.discard();
            return new Refused(vocabulary.commitFailed(), "the repository refused this change to "
                    + identifier + ": " + failed.getMessage());
        }
    }

    private static Outcome committedAs(Access access, Authorizable made, Vocabulary vocabulary) {
        try {
            return access.committed(new Made(principalOf(made)), vocabulary);
        } catch (final RepositoryException unreadable) {
            return new Refused(vocabulary.commitFailed(), "the repository did not report what it"
                    + " made: " + unreadable.getMessage());
        }
    }

    /**
     * One membership change, or the reason it was not made.
     *
     * <p>A group placed inside one of its own members would make every member of the outer group
     * a member of itself, which the repository does not refuse on its own. It is refused here
     * before anything changes.</p>
     */
    private static Outcome settled(Access reach, Group group, Authorizable member,
                                   MembershipChange change) throws RepositoryException {
        final boolean held = group.isDeclaredMember(member);
        if (change == MembershipChange.GRANTED && held
                || change == MembershipChange.WITHDRAWN && !held) {
            return new MembershipSettled(Settlement.ALREADY_AS_ASKED);
        }
        if (change == MembershipChange.GRANTED && (member.getID().equals(group.getID())
                || member.isGroup() && ((Group) member).isMember(group))) {
            return new Refused(CYCLE_REFUSED, "adding " + member.getID() + " to " + group.getID()
                    + " would make the group a member of itself");
        }
        final boolean changed = change == MembershipChange.GRANTED
                ? group.addMember(member) : group.removeMember(member);
        if (!changed) {
            return new Refused(MEMBERSHIPS.commitFailed(), "the repository did not change the"
                    + " membership of " + member.getID() + " in " + group.getID());
        }
        return reach.committed(new MembershipSettled(Settlement.CHANGED), MEMBERSHIPS);
    }

    /**
     * Makes one user or group with no password.
     *
     * <p>The user manager's own contract spells two things as an absent value, and both are what
     * this means: a user made with no password has none at all, rather than an empty one anybody
     * could sign in with; and an authorizable made with no place goes where the repository puts
     * them by default. Neither absence leaves this method.</p>
     */
    private static Optional<Authorizable> created(UserManager users, CreationRequest request)
            throws RepositoryException {
        final java.security.Principal principal =
                new NamedPrincipal(request.authorizableIdentifier());
        final boolean placed = !request.intermediatePath().isEmpty();
        try {
            if (request.kind() == Kind.GROUP) {
                return Optional.of(placed
                        ? users.createGroup(request.authorizableIdentifier(), principal,
                                request.intermediatePath())
                        : users.createGroup(principal));
            }
            // The user manager spells "no password at all" as an absent one; an empty password
            // would be one anybody could sign in with. policy/nullability.toml declares this.
            return Optional.of(placed
                    ? users.createUser(request.authorizableIdentifier(), null, principal,
                            request.intermediatePath())
                    : users.createUser(request.authorizableIdentifier(), null));
        } catch (final javax.jcr.nodetype.ConstraintViolationException
                       | IllegalArgumentException rejected) {
            return Optional.empty();
        }
    }

    /**
     * Records every property on one authorizable, or the first the repository refused.
     *
     * <p>Each value keeps the kind the caller stated, so a flag is stored as a flag and a date as
     * a date rather than every one of them as text.</p>
     */
    private static Optional<Refused> written(Authorizable authorizable,
                                             List<NamedValue> properties, ValueFactory values) {
        for (final NamedValue property : properties) {
            try {
                final List<Value> converted = new ArrayList<>();
                for (final PropertyScalar scalar : property.value().values()) {
                    converted.add(values.createValue(scalar.value(), typeOf(scalar)));
                }
                if (property.value() instanceof PropertyValue.Single) {
                    authorizable.setProperty(property.name(), converted.getFirst());
                } else {
                    authorizable.setProperty(property.name(), converted.toArray(Value[]::new));
                }
            } catch (final RepositoryException refused) {
                return Optional.of(new Refused(PROPERTY_REJECTED, property.name()
                        + " was refused by the repository: " + refused.getMessage()));
            }
        }
        return Optional.empty();
    }

    private static int typeOf(PropertyScalar scalar) {
        return switch (scalar.kind()) {
            case STRING -> PropertyType.STRING;
            case BOOLEAN -> PropertyType.BOOLEAN;
            case INTEGER -> PropertyType.LONG;
            case DECIMAL -> PropertyType.DECIMAL;
            case DATE_TIME -> PropertyType.DATE;
            case REPOSITORY_PATH -> PropertyType.PATH;
        };
    }

    private static Optional<Authorizable> user(UserManager users, String identifier)
            throws RepositoryException {
        final Authorizable found = users.getAuthorizable(identifier);
        return found == null || found.isGroup() ? Optional.empty() : Optional.of(found);
    }

    private static Optional<Group> group(UserManager users, String identifier)
            throws RepositoryException {
        final Authorizable found = users.getAuthorizable(identifier);
        return found instanceof final Group group ? Optional.of(group) : Optional.empty();
    }

    private static Outcome absentOrGroup(UserManager users, String identifier,
                                         Vocabulary vocabulary) throws RepositoryException {
        return users.getAuthorizable(identifier) == null
                ? new Refused(vocabulary.notFound(), identifier
                        + " names no user this caller can see")
                : new Refused(KIND_MISMATCH, identifier + " is a group, and this command"
                        + " changes a user");
    }

    private static Outcome absentOrUser(UserManager users, String identifier)
            throws RepositoryException {
        return users.getAuthorizable(identifier) == null
                ? new Refused(MEMBERSHIPS.notFound(), identifier
                        + " names no group this caller can see")
                : new Refused(KIND_MISMATCH, identifier + " is a user, and only a group has"
                        + " members");
    }

    private static PrincipalDirectory.Principal principalOf(Authorizable authorizable)
            throws RepositoryException {
        return new PrincipalDirectory.Principal(authorizable.getID(),
                authorizable.isGroup() ? Kind.GROUP : Kind.USER, authorizable.getPath());
    }

    private static String reasonFor(String reason) {
        return reason.isEmpty() ? "disabled through the Slingshot agent" : reason;
    }

    private static Refused accessDenied(String identifier) {
        return new Refused(ACCESS_DENIED, "this caller may not change or read " + identifier
                + ", and the repository refused it exactly as it would have by hand");
    }

    private static Outcome noSession(Vocabulary vocabulary) {
        return new Refused(ACCESS_DENIED, "this caller has no repository session through which"
                + " users and groups can be reached, so nothing was " + (vocabulary == CONTROLS
                        ? "changed" : "read or changed"));
    }

    /**
     * The caller's own user manager and value factory, or nothing where their resolver has none.
     */
    private static Optional<Access> reach(ResourceResolver resolver) {
        // The caller's own session, borrowed from their resolver and never closed here: it is
        // theirs, and closing it would close a session the request is still using.
        if (!(resolver.adaptTo(Session.class) instanceof final JackrabbitSession jackrabbit)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Access(jackrabbit, jackrabbit.getUserManager(),
                    jackrabbit.getValueFactory()));
        } catch (final RepositoryException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * What one call reaches the repository's users through.
     *
     * @param session the caller's own session
     * @param users its user manager
     * @param values its value factory
     */
    private record Access(Session session, UserManager users, ValueFactory values) {

        /** Takes back whatever this call changed and did not commit. */
        void discard() {
            try {
                session.refresh(false);
            } catch (final RepositoryException ignored) {
                // Nothing further can be taken back; the change was never committed.
                return;
            }
        }

        /** Commits this call's change and answers with what it did, or the reason it did not. */
        Outcome committed(Outcome done, Vocabulary vocabulary) {
            try {
                session.save();
                return done;
            } catch (final AccessDeniedException denied) {
                discard();
                return new Refused(ACCESS_DENIED, "the repository refused to commit this change"
                        + " for this caller");
            } catch (final RepositoryException failed) {
                discard();
                return new Refused(vocabulary.commitFailed(), "the repository did not commit"
                        + " this change: " + failed.getMessage());
            }
        }
    }

    /**
     * The principal a new authorizable is created with, named after its identifier.
     *
     * @param name the principal's name
     */
    private record NamedPrincipal(String name) implements java.security.Principal {

        @Override
        public String getName() {
            return name;
        }
    }
}
