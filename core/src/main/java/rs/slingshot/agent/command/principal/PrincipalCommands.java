// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.principal;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.platform.AccountState;
import rs.slingshot.agent.command.platform.PrincipalDirectory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the eight principal commands take.
 *
 * <p>Read in one place because the identifier is the same identifier in all eight and its bound is
 * the thing that must not drift: an identifier one command accepts and another refuses is a caller
 * who can make a user they cannot then change.</p>
 *
 * <p>No member here is a password, and none can be. The properties a creation or a profile change
 * carries are the ordinary property vocabulary this build already has — the same one a page update
 * uses — and the repository's own credential storage is not reachable through it.</p>
 */
public final class PrincipalCommands {

    private PrincipalCommands() {
    }

    /** The wire name of the command that makes a user. */
    public static final String CREATE_USER_WIRE_NAME = "create_user";

    /** The wire name of the command that makes a group. */
    public static final String CREATE_GROUP_WIRE_NAME = "create_group";

    /** The wire name of the command that changes what is recorded about a user. */
    public static final String UPDATE_PROFILE_WIRE_NAME = "update_user_profile";

    /** The wire name of the command that turns an account off or on. */
    public static final String SET_DISABLED_WIRE_NAME = "set_user_disabled";

    /** The wire name of the command that removes a user or a group. */
    public static final String DELETE_WIRE_NAME = "delete_authorizable";

    /** The wire name of the command that grants a membership. */
    public static final String ADD_MEMBER_WIRE_NAME = "add_group_member";

    /** The wire name of the command that takes one away. */
    public static final String REMOVE_MEMBER_WIRE_NAME = "remove_group_member";

    /** The wire name of the command that lists who is in a group. */
    public static final String LIST_MEMBERS_WIRE_NAME = "list_group_members";

    /** The member an authorizable's identifier is carried in. */
    public static final String AUTHORIZABLE_IDENTIFIER = "authorizable_identifier";

    /** The member the place under the authorizable tree is carried in. */
    public static final String INTERMEDIATE_PATH = "intermediate_path";

    /** The member the flag saying whether an account is off is carried in. */
    public static final String DISABLED = "disabled";

    /** The member the reason for turning an account off is carried in. */
    public static final String REASON = "reason";

    /** The member the kind a caller believes an authorizable is is carried in. */
    public static final String EXPECTED_KIND = "expected_kind";

    /** The member a group's identifier is carried in. */
    public static final String GROUP_IDENTIFIER = "group_identifier";

    /** The member a member's identifier is carried in. */
    public static final String MEMBER_IDENTIFIER = "member_identifier";

    /** The member saying whether members held through other groups count. */
    public static final String INCLUDE_INDIRECT = "include_indirect";

    /** What the place says when the caller named none, which is the repository's own default. */
    public static final String DEFAULT_PLACE = "";

    /** What the reason says when the caller wrote none. */
    public static final String NO_REASON = "";

    /**
     * The member the properties are carried in.
     *
     * <p>Spelled here rather than borrowed from the change vocabulary, because a creation takes
     * properties and no removals: borrowing the name would borrow the removal beside it, and this
     * command would then claim to take a member the client does not send.</p>
     */
    public static final String PROPERTIES = "properties";

    /** The member the removals are carried in, which only a profile change takes. */
    public static final String REMOVED_PROPERTY_NAMES = "removed_property_names";

    /** Every member a creation takes. */
    public static final List<String> CREATION_MEMBERS = List.of(AUTHORIZABLE_IDENTIFIER,
            PropertyValue.CARDINALITY, INTERMEDIATE_PATH, PROPERTIES, PropertyValue.VALUE);

    /** Every member a profile change takes. */
    public static final List<String> PROFILE_MEMBERS = List.of(AUTHORIZABLE_IDENTIFIER,
            PropertyValue.CARDINALITY, PROPERTIES, REMOVED_PROPERTY_NAMES, PropertyValue.VALUE);

    /** Every member the command that turns an account off takes. */
    public static final List<String> ACCOUNT_MEMBERS =
            List.of(AUTHORIZABLE_IDENTIFIER, DISABLED, REASON);

    /** Every member a removal takes. */
    public static final List<String> REMOVAL_MEMBERS =
            List.of(AUTHORIZABLE_IDENTIFIER, EXPECTED_KIND);

    /** Every member a membership change takes. */
    public static final List<String> MEMBERSHIP_MEMBERS =
            List.of(GROUP_IDENTIFIER, MEMBER_IDENTIFIER);

    /** Every member a membership listing takes. */
    public static final List<String> LISTING_MEMBERS =
            List.of(GROUP_IDENTIFIER, INCLUDE_INDIRECT, ResultWindow.ARGUMENT_MEMBER);

    /** Why an argument is not one these commands take. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** An identifier is empty, or longer than one may be. */
        IDENTIFIER_REJECTED,
        /** The place under the authorizable tree is empty, or longer than one may be. */
        PLACE_REJECTED,
        /** The properties are not ones this contract writes. */
        PROPERTIES_REJECTED,
        /** The reason is longer than the contract allows. */
        REASON_TOO_LONG,
        /** The account state is not a flag. */
        STATE_REJECTED,
        /** The expected kind is not one of the two there are. */
        KIND_REJECTED,
        /** The reach is not a flag. */
        REACH_REJECTED,
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /**
     * One refusal, said as the caller would fix it.
     *
     * @param refusal why the argument is not one this command takes
     * @param detail what was seen, which names no credential
     */
    public record Refused(Refusal refusal, String detail) {
    }

    /** What reading a creation produced. */
    public sealed interface CreationOutcome permits Creation, CreationRefused {
    }

    /**
     * A creation this command takes.
     *
     * @param request what to make
     */
    public record Creation(PrincipalDirectory.CreationRequest request) implements CreationOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record CreationRefused(Refused refusal) implements CreationOutcome {
    }

    /** What reading a profile change produced. */
    public sealed interface ProfileOutcome permits Profile, ProfileRefused {
    }

    /**
     * A profile change this command takes.
     *
     * @param authorizableIdentifier which user
     * @param change what to record and what to take away
     */
    public record Profile(String authorizableIdentifier, PropertyChange change)
            implements ProfileOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record ProfileRefused(Refused refusal) implements ProfileOutcome {
    }

    /** What reading an account change produced. */
    public sealed interface AccountOutcome permits Account, AccountRefused {
    }

    /**
     * An account change this command takes.
     *
     * @param authorizableIdentifier which user
     * @param state whether the account may be used
     * @param reason what to record about why, which may be {@link #NO_REASON}
     */
    public record Account(String authorizableIdentifier, AccountState state, String reason)
            implements AccountOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record AccountRefused(Refused refusal) implements AccountOutcome {
    }

    /** What reading a removal produced. */
    public sealed interface RemovalOutcome permits Removal, RemovalRefused {
    }

    /**
     * A removal this command takes.
     *
     * @param authorizableIdentifier which one
     * @param expectedKind what the caller believes it is
     */
    public record Removal(String authorizableIdentifier, PrincipalDirectory.Kind expectedKind)
            implements RemovalOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record RemovalRefused(Refused refusal) implements RemovalOutcome {
    }

    /** What reading a membership change produced. */
    public sealed interface MembershipOutcome permits MembershipPair, MembershipRefused {
    }

    /**
     * A membership change this command takes.
     *
     * @param groupIdentifier which group
     * @param memberIdentifier which authorizable
     */
    public record MembershipPair(String groupIdentifier, String memberIdentifier)
            implements MembershipOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record MembershipRefused(Refused refusal) implements MembershipOutcome {
    }

    /** What reading a membership listing produced. */
    public sealed interface ListingOutcome permits Listing, ListingRefused {
    }

    /**
     * A listing this command takes.
     *
     * @param groupIdentifier which group
     * @param reach whether members held through other groups count
     * @param window which page is wanted
     */
    public record Listing(String groupIdentifier, PrincipalDirectory.Reach reach,
                          ResultWindow window) implements ListingOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record ListingRefused(Refused refusal) implements ListingOutcome {
    }

    /**
     * Reads a creation's argument.
     *
     * @param arguments the argument document
     * @param kind whether a user or a group is being made
     * @param contract the authenticated contract, which bounds every member
     * @return the creation, or the one reason there is none
     */
    public static CreationOutcome creation(DocumentValue arguments, PrincipalDirectory.Kind kind,
                                           AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, CREATION_MEMBERS,
                List.of(AUTHORIZABLE_IDENTIFIER), contract);
        if (shape.isPresent()) {
            return new CreationRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final Optional<String> place = placeIn(mapping, contract);
        if (place.isEmpty()) {
            return new CreationRefused(new Refused(Refusal.PLACE_REJECTED, INTERMEDIATE_PATH
                    + " is where under the authorizable tree to put this: not empty, and within the "
                    + contract.value(ContractLimit.MAXIMUM_AUTHORIZABLE_INTERMEDIATE_PATH_BYTES)
                    + " one may be. Leave it out for the repository's own default."));
        }
        final PropertyChange.Outcome change = PropertyChange.of(mapping, contract);
        if (change instanceof final PropertyChange.Refused refused) {
            return new CreationRefused(new Refused(Refusal.PROPERTIES_REJECTED,
                    refused.refusal() + ": " + refused.detail()));
        }
        final List<PrincipalDirectory.NamedValue> properties = new java.util.ArrayList<>();
        ((PropertyChange.Held) change).change().set().forEach(
                (name, value) -> properties.add(new PrincipalDirectory.NamedValue(name, value)));
        return new Creation(new PrincipalDirectory.CreationRequest(identifierIn(mapping), kind,
                place.orElseThrow(), properties));
    }

    /**
     * Reads a profile change's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every member
     * @return the change, or the one reason there is none
     */
    public static ProfileOutcome profile(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, PROFILE_MEMBERS,
                List.of(AUTHORIZABLE_IDENTIFIER), contract);
        if (shape.isPresent()) {
            return new ProfileRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final PropertyChange.Outcome change = PropertyChange.of(mapping, contract);
        return change instanceof final PropertyChange.Refused refused
                ? new ProfileRefused(new Refused(Refusal.PROPERTIES_REJECTED,
                        refused.refusal() + ": " + refused.detail()))
                : new Profile(identifierIn(mapping), ((PropertyChange.Held) change).change());
    }

    /**
     * Reads an account change's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every member
     * @return the change, or the one reason there is none
     */
    public static AccountOutcome account(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, ACCOUNT_MEMBERS,
                List.of(AUTHORIZABLE_IDENTIFIER, DISABLED), contract);
        if (shape.isPresent()) {
            return new AccountRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final Optional<AccountState> state =
                AccountState.of(mapping.member(DISABLED).orElseThrow());
        if (state.isEmpty()) {
            return new AccountRefused(new Refused(Refusal.STATE_REJECTED,
                    DISABLED + " says whether the account may be used, and it is one of the two"
                            + " things a flag is"));
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_AUTHORIZABLE_DISABLED_REASON_BYTES);
        final Optional<DocumentValue> reason = mapping.member(REASON);
        if (reason.isPresent()
                && (!(reason.orElseThrow() instanceof final DocumentValue.Text text)
                        || text.value().length() > bound)) {
            return new AccountRefused(new Refused(Refusal.REASON_TOO_LONG,
                    REASON + " is text, within the " + bound + " a reason may be"));
        }
        return new Account(identifierIn(mapping), state.orElseThrow(), reason
                .filter(DocumentValue.Text.class::isInstance)
                .map(held -> ((DocumentValue.Text) held).value())
                .orElse(NO_REASON));
    }

    /**
     * Reads a removal's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every member
     * @return the removal, or the one reason there is none
     */
    public static RemovalOutcome removal(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, REMOVAL_MEMBERS,
                List.of(AUTHORIZABLE_IDENTIFIER, EXPECTED_KIND), contract);
        if (shape.isPresent()) {
            return new RemovalRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        if (!(mapping.member(EXPECTED_KIND).orElseThrow() instanceof final DocumentValue.Text kind)) {
            return new RemovalRefused(new Refused(Refusal.KIND_REJECTED,
                    EXPECTED_KIND + " is one of " + PrincipalDirectory.Kind.spellings()));
        }
        return PrincipalDirectory.Kind.named(kind.value())
                .<RemovalOutcome>map(expected -> new Removal(identifierIn(mapping), expected))
                .orElseGet(() -> new RemovalRefused(new Refused(Refusal.KIND_REJECTED,
                        kind.value() + " is neither of the two things an authorizable is; they are "
                                + PrincipalDirectory.Kind.spellings())));
    }

    /**
     * Reads a membership change's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds both identifiers
     * @return the pair, or the one reason there is none
     */
    public static MembershipOutcome membership(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape =
                shapeOf(arguments, MEMBERSHIP_MEMBERS, MEMBERSHIP_MEMBERS, contract);
        if (shape.isPresent()) {
            return new MembershipRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final Optional<String> group = named(mapping, GROUP_IDENTIFIER, contract);
        final Optional<String> member = named(mapping, MEMBER_IDENTIFIER, contract);
        return group.isEmpty() || member.isEmpty()
                ? new MembershipRefused(identifierRefusal(contract))
                : new MembershipPair(group.orElseThrow(), member.orElseThrow());
    }

    /**
     * Reads a membership listing's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier and the window
     * @return the listing, or the one reason there is none
     */
    public static ListingOutcome listing(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, LISTING_MEMBERS,
                List.of(GROUP_IDENTIFIER, INCLUDE_INDIRECT), contract);
        if (shape.isPresent()) {
            return new ListingRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final Optional<String> group = named(mapping, GROUP_IDENTIFIER, contract);
        if (group.isEmpty()) {
            return new ListingRefused(identifierRefusal(contract));
        }
        if (!(mapping.member(INCLUDE_INDIRECT).orElseThrow()
                instanceof final DocumentValue.Flag indirect)) {
            return new ListingRefused(new Refused(Refusal.REACH_REJECTED, INCLUDE_INDIRECT
                    + " says whether members held through other groups count, and it is one of the"
                    + " two things a flag is. It has no default: a group's direct members and"
                    + " everybody it grants anything to are different lists, and often very"
                    + " different lengths."));
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new ListingRefused(new Refused(Refusal.WINDOW_REFUSED,
                        refused.refusal().toString()))
                : new Listing(group.orElseThrow(),
                        indirect.value() == DocumentValue.Truth.TRUE
                                ? PrincipalDirectory.Reach.INCLUDING_INDIRECT
                                : PrincipalDirectory.Reach.DIRECT_ONLY,
                        ((ResultWindow.Held) window).window());
    }

    /**
     * The argument as the document its shape has already been checked against.
     *
     * <p>Re-tested rather than cast, because a cast here would be a promise about what
     * {@code shapeOf} did rather than a fact the compiler can see. The two are the same today and
     * a cast would stay compiling on the day they stop being.</p>
     *
     * @param arguments the argument document
     * @return it as a mapping, which its shape check has already proved it is
     */
    private static DocumentValue.Mapping held(DocumentValue arguments) {
        if (arguments instanceof final DocumentValue.Mapping mapping) {
            return mapping;
        }
        throw new IllegalStateException("the shape check accepted something that is not a"
                + " document, which cannot happen and would be a defect in it rather than in the"
                + " caller's argument");
    }

    private static Optional<Refused> shapeOf(DocumentValue arguments, List<String> members,
                                             List<String> required, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return Optional.of(new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming one user or group"));
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !members.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return Optional.of(new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument"));
        }
        final Optional<String> absent = required.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return Optional.of(new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required;"
                    + " group membership is how every permission here is granted, so nothing about"
                    + " one is something this side may choose"));
        }
        return members.contains(AUTHORIZABLE_IDENTIFIER)
                        && named(mapping, AUTHORIZABLE_IDENTIFIER, contract).isEmpty()
                ? Optional.of(identifierRefusal(contract)) : Optional.empty();
    }

    private static Refused identifierRefusal(AgentContract contract) {
        return new Refused(Refusal.IDENTIFIER_REJECTED, "an identifier is what a user or a group is"
                + " called: not empty, and within the "
                + contract.value(ContractLimit.MAXIMUM_AUTHORIZABLE_IDENTIFIER_BYTES)
                + " one may be");
    }

    private static Optional<String> named(DocumentValue.Mapping mapping, String member,
                                          AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_AUTHORIZABLE_IDENTIFIER_BYTES);
        final Optional<DocumentValue> asked = mapping.member(member);
        if (asked.isEmpty() || !(asked.orElseThrow() instanceof final DocumentValue.Text text)
                || text.value().isEmpty() || text.value().length() > bound) {
            return Optional.empty();
        }
        return Optional.of(text.value());
    }

    private static String identifierIn(DocumentValue.Mapping mapping) {
        return ((DocumentValue.Text) mapping.member(AUTHORIZABLE_IDENTIFIER).orElseThrow()).value();
    }

    private static Optional<String> placeIn(DocumentValue.Mapping mapping,
                                            AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(INTERMEDIATE_PATH);
        if (asked.isEmpty()) {
            return Optional.of(DEFAULT_PLACE);
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_AUTHORIZABLE_INTERMEDIATE_PATH_BYTES);
        return asked.orElseThrow() instanceof final DocumentValue.Text text
                && !text.value().isEmpty() && text.value().length() <= bound
                ? Optional.of(text.value()) : Optional.empty();
    }
}
