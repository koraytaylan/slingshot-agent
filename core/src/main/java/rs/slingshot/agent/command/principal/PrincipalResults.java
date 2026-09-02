// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.principal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.PrincipalDirectory;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the eight principal commands answer.
 *
 * <p>No credential appears in any of them, and there is no member one could travel in. What comes
 * back is an identifier, a kind, and where the repository holds it — enough to go and look, and
 * nothing that would help anybody sign in as somebody else.</p>
 *
 * <p>A membership change says whether it needed to do anything. A caller granting a permission has
 * to know whether they granted it or found it already there, because those are different sentences
 * in an audit and the second one is often the surprising half of an investigation.</p>
 */
public final class PrincipalResults {

    private PrincipalResults() {
    }

    /** The member an authorizable's identifier is carried in. */
    public static final String AUTHORIZABLE_IDENTIFIER = "authorizable_identifier";

    /** The member the kind is carried in. */
    public static final String KIND = "kind";

    /** The member where the repository holds it is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the flag saying whether an account is off is carried in. */
    public static final String DISABLED = "disabled";

    /** The member a group's identifier is carried in. */
    public static final String GROUP_IDENTIFIER = "group_identifier";

    /** The member a member's identifier is carried in. */
    public static final String MEMBER_IDENTIFIER = "member_identifier";

    /** The member saying the group already held the member. */
    public static final String ALREADY_A_MEMBER = "already_a_member";

    /** The member saying the group did hold the member. */
    public static final String WAS_A_MEMBER = "was_a_member";

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member saying a member is held by the group itself. */
    public static final String DIRECT = "direct";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member a creation's answer has. */
    public static final List<String> CREATION_MEMBERS =
            List.of(AUTHORIZABLE_IDENTIFIER, KIND, REPOSITORY_PATH);

    /** Every member a profile change's answer has. */
    public static final List<String> PROFILE_MEMBERS =
            List.of(AUTHORIZABLE_IDENTIFIER, REPOSITORY_PATH);

    /** Every member an account change's answer has. */
    public static final List<String> ACCOUNT_MEMBERS = List.of(AUTHORIZABLE_IDENTIFIER, DISABLED);

    /** Every member a removal's answer has. */
    public static final List<String> REMOVAL_MEMBERS =
            List.of(AUTHORIZABLE_IDENTIFIER, KIND, REPOSITORY_PATH);

    /** Every member a granted membership's answer has. */
    public static final List<String> GRANT_MEMBERS =
            List.of(ALREADY_A_MEMBER, GROUP_IDENTIFIER, MEMBER_IDENTIFIER);

    /** Every member a withdrawn membership's answer has. */
    public static final List<String> WITHDRAWAL_MEMBERS =
            List.of(GROUP_IDENTIFIER, MEMBER_IDENTIFIER, WAS_A_MEMBER);

    /** Every member a membership listing has. */
    public static final List<String> LISTING_MEMBERS = List.of(AUTHORIZABLE_IDENTIFIER, DIRECT,
            KIND, MATCHES, NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH);

    /** What the token member says when this is the last page. */
    public static final String NO_MORE_PAGES = "";

    /**
     * The result one creation or removal produces.
     *
     * @param principal what was made or removed
     * @return the result document
     */
    public static DocumentValue.Mapping principalOf(PrincipalDirectory.Principal principal) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(AUTHORIZABLE_IDENTIFIER,
                new DocumentValue.Text(principal.authorizableIdentifier()));
        result.put(KIND, new DocumentValue.Text(principal.kind().spelling()));
        result.put(REPOSITORY_PATH, new DocumentValue.Text(principal.repositoryPath()));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one profile change produces.
     *
     * @param authorizableIdentifier which user
     * @param repositoryPath where the repository holds it
     * @return the result document
     */
    public static DocumentValue.Mapping profileOf(String authorizableIdentifier,
                                                  String repositoryPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(authorizableIdentifier));
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one account change produces.
     *
     * @param authorizableIdentifier which user
     * @param state whether the account may be used
     * @return the result document
     */
    public static DocumentValue.Mapping accountOf(
            String authorizableIdentifier, rs.slingshot.agent.command.platform.AccountState state) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(authorizableIdentifier));
        result.put(DISABLED, state.flag());
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one membership change produces.
     *
     * @param groupIdentifier which group
     * @param memberIdentifier which authorizable
     * @param change whether the membership was granted or withdrawn
     * @param settlement whether the group was already as asked
     * @return the result document
     */
    public static DocumentValue.Mapping membershipOf(
            String groupIdentifier, String memberIdentifier,
            PrincipalDirectory.MembershipChange change,
            PrincipalDirectory.Settlement settlement) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(GROUP_IDENTIFIER, new DocumentValue.Text(groupIdentifier));
        result.put(MEMBER_IDENTIFIER, new DocumentValue.Text(memberIdentifier));
        // Granting says "it was already there" and withdrawing says "it was there". Two members
        // rather than one because the two commands answer opposite questions, and a caller reading
        // an audit needs the sentence to be true rather than merely consistent.
        result.put(change == PrincipalDirectory.MembershipChange.GRANTED
                        ? ALREADY_A_MEMBER : WAS_A_MEMBER,
                new DocumentValue.Flag(
                        settlement == PrincipalDirectory.Settlement.ALREADY_AS_ASKED
                                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one membership listing produces.
     *
     * @param members what it found, in the directory's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping membersOf(List<PrincipalDirectory.Member> members,
                                                  String nextContinuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(members.stream()
                .map(PrincipalResults::memberOf)
                .toList()));
        if (!NO_MORE_PAGES.equals(nextContinuationToken)) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(nextContinuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue memberOf(PrincipalDirectory.Member member) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(AUTHORIZABLE_IDENTIFIER,
                new DocumentValue.Text(member.principal().authorizableIdentifier()));
        match.put(KIND, new DocumentValue.Text(member.principal().kind().spelling()));
        match.put(REPOSITORY_PATH,
                new DocumentValue.Text(member.principal().repositoryPath()));
        match.put(DIRECT, new DocumentValue.Flag(
                member.membership() == PrincipalDirectory.Membership.DIRECT
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(match);
    }
}
