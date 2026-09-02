// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.principal;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.PrincipalDirectories;
import rs.slingshot.agent.command.platform.PrincipalDirectory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The eight commands about who may use this instance and what they may do.
 *
 * <p>Seven of the eight pass the control gate and one does not, and the odd one out is the listing.
 * Reading who is in a group is how somebody works out why a person can do something they should not
 * be able to, and refusing that on a deployment that will not let memberships be changed would
 * refuse the investigation along with the change.</p>
 *
 * <p>Every one of the eight acts through the caller's own session. That is the whole access-control
 * story: this agent grants nothing the caller could not have granted by hand, so a caller who could
 * not add themselves to the administrators group by hand cannot do it here either.</p>
 */
public final class PrincipalHandler implements CommandHandler {

    /** The category an authorizable nothing is called is refused under. */
    public static final String NOT_FOUND = "authorizable_not_found";

    /** The category an authorizable the caller may not reach is refused under. */
    public static final String ACCESS_DENIED = "authorizable_access_denied";

    /** The category an authorizable that is there and is the other kind is refused under. */
    public static final String KIND_MISMATCH = "authorizable_kind_mismatch";

    /** The category an authorizable already at that identifier is refused under. */
    public static final String ALREADY_EXISTS = "authorizable_already_exists";

    /** The category an identifier this repository will not hold is refused under. */
    public static final String IDENTIFIER_REJECTED = "identifier_rejected";

    /** The category a place under the authorizable tree this build will not use is refused under. */
    public static final String PLACE_REJECTED = "intermediate_path_rejected";

    /** The category a property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a property the repository will not let go of is refused under. */
    public static final String PROPERTY_NOT_REMOVABLE = "property_not_removable";

    /** The category a group that still holds members is refused under. */
    public static final String GROUP_HAS_MEMBERS = "group_has_members";

    /** The category a group nothing is called is refused under. */
    public static final String GROUP_NOT_FOUND = "group_not_found";

    /** The category a member nothing is called is refused under. */
    public static final String MEMBER_NOT_FOUND = "member_not_found";

    /** The category a membership that would make a group hold itself is refused under. */
    public static final String MEMBERSHIP_CYCLE_REFUSED = "membership_cycle_refused";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    /** The category the platform refusing a control is reported under. */
    public static final String CONTROL_REJECTED = "platform_control_rejected";

    /** The five ways a continuation token can be refused, which every paged command declares. */
    public static final List<String> CONTINUATION_CATEGORIES = List.of(
            "continuation_token_malformed", "continuation_token_integrity_invalid",
            "continuation_token_wrong_target", "continuation_token_wrong_query",
            "continuation_token_expired");

    /** Which of the eight this handler answers. */
    public enum Kind {
        /** Makes a user. */
        USER_CREATION,
        /** Makes a group. */
        GROUP_CREATION,
        /** Changes what is recorded about a user. */
        PROFILE,
        /** Turns an account off or on. */
        ACCOUNT,
        /** Removes a user or a group. */
        REMOVAL,
        /** Grants a membership. */
        GRANT,
        /** Takes one away. */
        WITHDRAWAL,
        /** Lists who is in a group. */
        LISTING
    }

    private final AgentContract contract;
    private final Kind kind;
    private final PrincipalDirectories directories;
    private final PlatformControl control;

    /**
     * Holds one handler for one of the eight.
     *
     * @param contract the authenticated contract
     * @param kind which of the eight commands this handler answers
     * @param directories where one run gets its own view of the users and groups
     * @param control what this deployment permits, asked before any of the seven changes proceeds
     */
    public PrincipalHandler(AgentContract contract, Kind kind, PrincipalDirectories directories,
                            PlatformControl control) {
        this.contract = contract;
        this.kind = kind;
        this.directories = directories;
        this.control = control;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        if (kind == Kind.LISTING) {
            return listed(arguments, resolver, context);
        }
        final PlatformControl.Verdict verdict =
                control.permits(ControlCapability.PRINCIPAL_ADMINISTRATION);
        if (verdict instanceof final PlatformControl.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        return changed(arguments, resolver);
    }

    private Answer changed(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        return switch (kind) {
            case USER_CREATION -> made(arguments, PrincipalDirectory.Kind.USER, resolver);
            case GROUP_CREATION -> made(arguments, PrincipalDirectory.Kind.GROUP, resolver);
            case PROFILE -> profiled(arguments, resolver);
            case ACCOUNT -> accounted(arguments, resolver);
            case REMOVAL -> removed(arguments, resolver);
            case GRANT -> membership(arguments, PrincipalDirectory.MembershipChange.GRANTED,
                    resolver);
            case WITHDRAWAL -> membership(arguments,
                    PrincipalDirectory.MembershipChange.WITHDRAWN, resolver);
            case LISTING -> new Failed(GROUP_NOT_FOUND, "this listing takes no control gate");
        };
    }

    private Answer made(DocumentValue.Mapping arguments, PrincipalDirectory.Kind wanted,
                        ResourceResolver resolver) {
        final PrincipalCommands.CreationOutcome asked =
                PrincipalCommands.creation(arguments, wanted, contract);
        if (asked instanceof final PrincipalCommands.CreationRefused refused) {
            return new Failed(categoryFor(refused.refusal().refusal()),
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final PrincipalDirectory.Outcome made = directories.open().make(
                ((PrincipalCommands.Creation) asked).request(), resolver);
        return made instanceof final PrincipalDirectory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(PrincipalResults.principalOf(
                        ((PrincipalDirectory.Made) made).principal()));
    }

    private Answer profiled(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final PrincipalCommands.ProfileOutcome asked =
                PrincipalCommands.profile(arguments, contract);
        if (asked instanceof final PrincipalCommands.ProfileRefused refused) {
            return new Failed(categoryFor(refused.refusal().refusal()),
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final PrincipalCommands.Profile profile = (PrincipalCommands.Profile) asked;
        final PrincipalDirectory.Outcome written = directories.open().applyProfile(
                profile.authorizableIdentifier(), profile.change().set(),
                profile.change().removed(), resolver);
        return written instanceof final PrincipalDirectory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(PrincipalResults.profileOf(profile.authorizableIdentifier(),
                        ((PrincipalDirectory.Changed) written).repositoryPath()));
    }

    private Answer accounted(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final PrincipalCommands.AccountOutcome asked =
                PrincipalCommands.account(arguments, contract);
        if (asked instanceof final PrincipalCommands.AccountRefused refused) {
            return new Failed(categoryFor(refused.refusal().refusal()),
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final PrincipalCommands.Account account = (PrincipalCommands.Account) asked;
        final PrincipalDirectory.Outcome set = directories.open().applyAccountState(
                account.authorizableIdentifier(), account.state(), account.reason(), resolver);
        return set instanceof final PrincipalDirectory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(PrincipalResults.accountOf(account.authorizableIdentifier(),
                        account.state()));
    }

    private Answer removed(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final PrincipalCommands.RemovalOutcome asked =
                PrincipalCommands.removal(arguments, contract);
        if (asked instanceof final PrincipalCommands.RemovalRefused refused) {
            return new Failed(categoryFor(refused.refusal().refusal()),
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final PrincipalCommands.Removal removal = (PrincipalCommands.Removal) asked;
        final PrincipalDirectory.Outcome gone = directories.open().erase(
                removal.authorizableIdentifier(), removal.expectedKind(), resolver);
        return gone instanceof final PrincipalDirectory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(PrincipalResults.principalOf(
                        ((PrincipalDirectory.Removed) gone).principal()));
    }

    private Answer membership(DocumentValue.Mapping arguments,
                              PrincipalDirectory.MembershipChange change,
                              ResourceResolver resolver) {
        final PrincipalCommands.MembershipOutcome asked =
                PrincipalCommands.membership(arguments, contract);
        if (asked instanceof final PrincipalCommands.MembershipRefused refused) {
            return new Failed(GROUP_NOT_FOUND,
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final PrincipalCommands.MembershipPair pair = (PrincipalCommands.MembershipPair) asked;
        final PrincipalDirectory.Outcome settled = directories.open().applyMembership(
                pair.groupIdentifier(), pair.memberIdentifier(), change, resolver);
        return settled instanceof final PrincipalDirectory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(PrincipalResults.membershipOf(pair.groupIdentifier(),
                        pair.memberIdentifier(), change,
                        ((PrincipalDirectory.MembershipSettled) settled).settlement()));
    }

    private Answer listed(DocumentValue.Mapping arguments, ResourceResolver resolver,
                          CallerContext context) {
        final PrincipalCommands.ListingOutcome asked =
                PrincipalCommands.listing(arguments, contract);
        if (asked instanceof final PrincipalCommands.ListingRefused refused) {
            return new Failed(GROUP_NOT_FOUND,
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final PrincipalCommands.Listing listing = (PrincipalCommands.Listing) asked;
        final PrincipalDirectory.Outcome found =
                directories.open().members(listing.groupIdentifier(), listing.reach(), resolver);
        if (found instanceof final PrincipalDirectory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<PrincipalDirectory.Member> members =
                ((PrincipalDirectory.Members) found).members();
        return members.size() > context.discovery().limit()
                ? new Failed(DISCOVERY_BUDGET_EXCEEDED, members.size() + " members is more than"
                        + " the " + context.discovery().limit() + " this caller may examine")
                : new Produced(PrincipalResults.membersOf(pageOf(members, listing.window()),
                        PrincipalResults.NO_MORE_PAGES));
    }

    /**
     * The window's worth of members.
     *
     * @param members every member the directory holds, in its own order
     * @param window which page is wanted
     * @return the members that page carries
     */
    public static List<PrincipalDirectory.Member> pageOf(List<PrincipalDirectory.Member> members,
                                                         ResultWindow window) {
        if (!(window instanceof final ResultWindow.Initial initial)) {
            return members;
        }
        return members.stream().skip(initial.offset()).limit(initial.limit()).toList();
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(PrincipalCommands.Refusal refusal) {
        return switch (refusal) {
            case IDENTIFIER_REJECTED -> IDENTIFIER_REJECTED;
            case PLACE_REJECTED -> PLACE_REJECTED;
            case PROPERTIES_REJECTED -> PROPERTY_REJECTED;
            case KIND_REJECTED -> KIND_MISMATCH;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, REASON_TOO_LONG, STATE_REJECTED,
                    REACH_REJECTED, WINDOW_REFUSED -> NOT_FOUND;
        };
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case USER_CREATION, GROUP_CREATION -> creationCategories();
            case PROFILE -> profileCategories();
            case ACCOUNT -> accountCategories();
            case REMOVAL -> removalCategories();
            case GRANT, WITHDRAWAL -> membershipCategories();
            case LISTING -> listingCategories();
        };
    }

    /**
     * Everything one creation can fail with.
     *
     * @return the categories
     */
    public static List<String> creationCategories() {
        return List.of(ACCESS_DENIED, ALREADY_EXISTS, IDENTIFIER_REJECTED, PLACE_REJECTED,
                PROPERTY_REJECTED, COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one profile change can fail with.
     *
     * @return the categories
     */
    public static List<String> profileCategories() {
        return List.of(ACCESS_DENIED, KIND_MISMATCH, NOT_FOUND, PROPERTY_NOT_REMOVABLE,
                PROPERTY_REJECTED, COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one account change can fail with.
     *
     * @return the categories
     */
    public static List<String> accountCategories() {
        return List.of(ACCESS_DENIED, KIND_MISMATCH, NOT_FOUND, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }

    /**
     * Everything one removal can fail with.
     *
     * @return the categories
     */
    public static List<String> removalCategories() {
        return List.of(ACCESS_DENIED, KIND_MISMATCH, NOT_FOUND, GROUP_HAS_MEMBERS, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one membership change can fail with.
     *
     * @return the categories
     */
    public static List<String> membershipCategories() {
        return List.of(ACCESS_DENIED, KIND_MISMATCH, GROUP_NOT_FOUND, MEMBER_NOT_FOUND,
                MEMBERSHIP_CYCLE_REFUSED, COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one membership listing can fail with.
     *
     * @return the categories
     */
    public static List<String> listingCategories() {
        final List<String> categories = new java.util.ArrayList<>(
                List.of(ACCESS_DENIED, KIND_MISMATCH, DISCOVERY_BUDGET_EXCEEDED, GROUP_NOT_FOUND));
        categories.addAll(CONTINUATION_CATEGORIES);
        return List.copyOf(categories);
    }
}
