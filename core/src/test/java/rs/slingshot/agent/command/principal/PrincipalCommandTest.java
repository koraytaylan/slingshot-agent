// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.platform.AccountState;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.PrincipalDirectory;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The eight commands about who may use this instance and what they may do.
 *
 * <p>Three rules are under test throughout. No password is ever set, read, or carried. Every change
 * goes through the caller's own session, so this agent grants nothing the caller could not have
 * granted by hand. And a group with members is not deleted, because cascading would remove
 * permissions from people who are not in the request and have no idea it happened.</p>
 */
final class PrincipalCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String USER = "jdoe";

    private static final String GROUP = "authors";

    @Test
    @DisplayName("no command takes a password, and none of the eight answers with one")
    void nocommandTakesAPassword() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(USER));
        members.put("password", new DocumentValue.Text("hunter2"));
        assertEquals(PrincipalCommands.Refusal.MEMBER_UNKNOWN,
                assertInstanceOf(PrincipalCommands.CreationRefused.class,
                        PrincipalCommands.creation(new DocumentValue.Mapping(members),
                                PrincipalDirectory.Kind.USER, CONTRACT),
                        "a password was accepted, and an agent that can set one is an agent that"
                                + " can become anybody").refusal().refusal());
        assertTrue(!PrincipalResults.CREATION_MEMBERS.contains("password")
                        && !PrincipalResults.PROFILE_MEMBERS.contains("password"),
                "a result member exists that a credential could travel in");
        final DocumentValue.Mapping made = assertInstanceOf(CommandHandler.Produced.class,
                run(PrincipalHandler.Kind.USER_CREATION, creation(USER)),
                "the creation was refused").result();
        assertTrue(!String.valueOf(made).contains("hunter2"),
                "a credential reached the answer: " + made);
    }

    @Test
    @DisplayName("every change acts through the caller's own session, and the listing does too")
    void everychangeActsThroughTheCallersSession() {
        final Directory directory = new Directory();
        for (final var pair : attempts()) {
            new PrincipalHandler(CONTRACT, pair.getKey(), () -> directory, permissive())
                    .run(pair.getValue(), null, context());
        }
        assertEquals(attempts().size(), directory.calls().size(),
                "a command reached the directory without handing it the caller's own session,"
                        + " which is the whole access-control story: this agent grants nothing the"
                        + " caller could not have granted by hand");
    }

    @Test
    @DisplayName("a deployment without principal administration refuses the seven, not the listing")
    void thelistingSurvivesADeploymentThatPermitsNoChange() {
        final Directory directory = new Directory();
        final PlatformControl refusing = PlatformControl.of("aem-cloud-service", Set.of());
        for (final var pair : attempts()) {
            if (pair.getKey() == PrincipalHandler.Kind.LISTING) {
                continue;
            }
            assertEquals(PlatformControl.NOT_PERMITTED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new PrincipalHandler(CONTRACT, pair.getKey(), () -> directory, refusing)
                                    .run(pair.getValue(), null, context()),
                            pair.getKey() + " was carried out on a deployment that does not permit"
                                    + " it").category());
        }
        assertEquals(List.of(), directory.calls(),
                "the directory was asked to change something on a deployment that does not permit"
                        + " it");
        assertInstanceOf(CommandHandler.Produced.class,
                new PrincipalHandler(CONTRACT, PrincipalHandler.Kind.LISTING, () -> directory, refusing)
                        .run(listing(GROUP, false), null, context()),
                "reading who is in a group was refused, and that is how somebody works out why a"
                        + " person can do something they should not be able to");
    }

    @Test
    @DisplayName("a removal says what the caller believes it is removing, and a mismatch is refused")
    void aremovalCarriesTheExpectedKind() {
        assertEquals(PrincipalCommands.Refusal.MEMBER_ABSENT,
                assertInstanceOf(PrincipalCommands.RemovalRefused.class,
                        PrincipalCommands.removal(identifierNamed(
                                PrincipalCommands.AUTHORIZABLE_IDENTIFIER, USER), CONTRACT),
                        "a removal naming no expected kind was accepted, and removing a group"
                                + " when you meant a user takes permissions from everybody in it")
                        .refusal().refusal());
        assertEquals(PrincipalCommands.Refusal.KIND_REJECTED,
                assertInstanceOf(PrincipalCommands.RemovalRefused.class,
                        PrincipalCommands.removal(removal(USER, "person"), CONTRACT),
                        "a kind nobody publishes was accepted").refusal().refusal());
        assertEquals(PrincipalDirectory.Kind.GROUP,
                assertInstanceOf(PrincipalCommands.Removal.class,
                        PrincipalCommands.removal(removal(GROUP, "group"), CONTRACT),
                        "a removal naming a kind was refused").expectedKind());
        assertEquals(PrincipalHandler.KIND_MISMATCH,
                PrincipalHandler.categoryFor(PrincipalCommands.Refusal.KIND_REJECTED));
    }

    @Test
    @DisplayName("a group that still holds members is refused rather than cascaded")
    void agroupWithMembersIsRefused() {
        final Directory refusing = new Directory(PrincipalHandler.GROUP_HAS_MEMBERS,
                "it still holds two members");
        assertEquals(PrincipalHandler.GROUP_HAS_MEMBERS,
                assertInstanceOf(CommandHandler.Failed.class,
                        new PrincipalHandler(CONTRACT, PrincipalHandler.Kind.REMOVAL, () -> refusing,
                                permissive()).run(removal(GROUP, "group"), null, context()),
                        "a group that still holds members was removed, and cascading takes"
                                + " permissions from people who are not in the request and have no"
                                + " idea it happened").category());
        assertTrue(PrincipalHandler.removalCategories()
                        .contains(PrincipalHandler.GROUP_HAS_MEMBERS),
                "the removal no longer declares the refusal that keeps it from cascading");
    }

    @Test
    @DisplayName("a membership that would make a group hold itself is refused before the commit")
    void amembershipCycleIsRefused() {
        final Directory refusing = new Directory(PrincipalHandler.MEMBERSHIP_CYCLE_REFUSED,
                "that would make the group hold itself");
        assertEquals(PrincipalHandler.MEMBERSHIP_CYCLE_REFUSED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new PrincipalHandler(CONTRACT, PrincipalHandler.Kind.GRANT, () -> refusing,
                                permissive()).run(membership(GROUP, GROUP), null, context()),
                        "a membership making a group hold itself was granted").category());
        assertTrue(PrincipalHandler.membershipCategories()
                        .contains(PrincipalHandler.MEMBERSHIP_CYCLE_REFUSED),
                "a membership change no longer declares the cycle refusal");
    }

    @Test
    @DisplayName("a membership change says whether it granted a permission or found it")
    void amembershipChangeSaysWhetherItDidAnything() {
        final DocumentValue.Mapping granted = assertInstanceOf(CommandHandler.Produced.class,
                run(PrincipalHandler.Kind.GRANT, membership(GROUP, USER)),
                "the grant was refused").result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                granted.member(PrincipalResults.ALREADY_A_MEMBER).orElseThrow(),
                "a grant does not say whether the membership was already there, and a caller"
                        + " granting a permission needs to know whether they granted it or found"
                        + " it — those are different sentences in an audit");
        assertTrue(granted.member(PrincipalResults.WAS_A_MEMBER).isEmpty(),
                "a grant answered the withdrawal's own member, whose sentence would be false");
        final DocumentValue.Mapping withdrawn = assertInstanceOf(CommandHandler.Produced.class,
                run(PrincipalHandler.Kind.WITHDRAWAL, membership(GROUP, USER)),
                "the withdrawal was refused").result();
        assertTrue(withdrawn.member(PrincipalResults.WAS_A_MEMBER).isPresent()
                        && withdrawn.member(PrincipalResults.ALREADY_A_MEMBER).isEmpty(),
                "a withdrawal answered the grant's own member: " + withdrawn);
    }

    @Test
    @DisplayName("a listing says how far it looked, and there is no default for that")
    void alistingSaysHowFarItLooked() {
        assertEquals(PrincipalCommands.Refusal.MEMBER_ABSENT,
                assertInstanceOf(PrincipalCommands.ListingRefused.class,
                        PrincipalCommands.listing(identifierNamed(
                                PrincipalCommands.GROUP_IDENTIFIER, GROUP), CONTRACT),
                        "a listing naming no reach was accepted, and a group's direct members and"
                                + " everybody it grants anything to are different lists")
                        .refusal().refusal());
        assertEquals(PrincipalDirectory.Reach.INCLUDING_INDIRECT,
                assertInstanceOf(PrincipalCommands.Listing.class,
                        PrincipalCommands.listing(listing(GROUP, true), CONTRACT),
                        "a listing naming a reach was refused").reach());
        final DocumentValue.Mapping listed = assertInstanceOf(CommandHandler.Produced.class,
                run(PrincipalHandler.Kind.LISTING, listing(GROUP, true)),
                "the listing was refused").result();
        final List<DocumentValue> matches = assertInstanceOf(DocumentValue.Sequence.class,
                listed.member(PrincipalResults.MATCHES).orElseThrow()).items();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                assertInstanceOf(DocumentValue.Mapping.class, matches.getFirst())
                        .member(PrincipalResults.DIRECT).orElseThrow());
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                assertInstanceOf(DocumentValue.Mapping.class, matches.get(1))
                        .member(PrincipalResults.DIRECT).orElseThrow(),
                "a member held through another group was reported as held directly, which is the"
                        + " difference between a permission somebody granted and one they"
                        + " inherited");
    }

    @Test
    @DisplayName("an account is turned off by a named state read from the flag the client sends")
    void anaccountIsTurnedOffByAName() {
        final PrincipalCommands.Account held = assertInstanceOf(PrincipalCommands.Account.class,
                PrincipalCommands.account(account(USER, true, "left the company"), CONTRACT),
                "an account change was refused");
        assertEquals(AccountState.DISABLED, held.state());
        assertEquals("left the company", held.reason());
        assertEquals(AccountState.ENABLED,
                assertInstanceOf(PrincipalCommands.Account.class,
                        PrincipalCommands.account(account(USER, false, ""), CONTRACT),
                        "an account change was refused").state());
        final DocumentValue.Mapping answered = assertInstanceOf(CommandHandler.Produced.class,
                run(PrincipalHandler.Kind.ACCOUNT, account(USER, true, "")),
                "the account change was refused").result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                answered.member(PrincipalResults.DISABLED).orElseThrow(),
                "the answer does not carry the flag the client reads");
    }

    @Test
    @DisplayName("a creation takes the ordinary property vocabulary and refuses a bare value")
    void acreationTakesTypedProperties() {
        final SequencedMap<String, DocumentValue> bare = new LinkedHashMap<>();
        bare.put("profile/givenName", new DocumentValue.Text("Jane"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(USER));
        members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(bare));
        assertEquals(PrincipalCommands.Refusal.PROPERTIES_REJECTED,
                assertInstanceOf(PrincipalCommands.CreationRefused.class,
                        PrincipalCommands.creation(new DocumentValue.Mapping(members),
                                PrincipalDirectory.Kind.USER, CONTRACT),
                        "a value with no cardinality beside it was accepted")
                        .refusal().refusal());
        final PrincipalDirectory.CreationRequest request = assertInstanceOf(
                PrincipalCommands.Creation.class,
                PrincipalCommands.creation(creation(USER), PrincipalDirectory.Kind.USER, CONTRACT),
                "a typed creation was refused").request();
        assertEquals(PrincipalDirectory.Kind.USER, request.kind());
        assertEquals(PrincipalCommands.DEFAULT_PLACE, request.intermediatePath(),
                "a creation naming no place was given one, and the repository has its own default");
        assertTrue(request.properties().stream()
                        .anyMatch(named -> "profile/givenName".equals(named.name())),
                "the creation does not carry the property the caller named");
    }

    @Test
    @DisplayName("a repository that could not be asked is reported as it saying so, in all eight")
    void arepositoryFailureReachesEveryCommand() {
        final Directory refusing = new Directory(PrincipalHandler.ACCESS_DENIED,
                "this caller may not reach it");
        for (final var pair : attempts()) {
            assertEquals(PrincipalHandler.ACCESS_DENIED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new PrincipalHandler(CONTRACT, pair.getKey(), () -> refusing, permissive())
                                    .run(pair.getValue(), null, context()),
                            pair.getKey() + " reported a repository refusal as an answer")
                            .category());
        }
    }

    @Test
    @DisplayName("an argument none of the eight takes is refused before the repository is asked")
    void abadArgumentNeverReachesTheRepository() {
        final Directory directory = new Directory();
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("user_name", new DocumentValue.Text(USER));
        for (final var pair : attempts()) {
            assertInstanceOf(CommandHandler.Failed.class,
                    new PrincipalHandler(CONTRACT, pair.getKey(), () -> directory, permissive())
                            .run(new DocumentValue.Mapping(unknown), null, context()),
                    pair.getKey() + " accepted a member nobody declared");
        }
        assertEquals(List.of(), directory.calls(),
                "the repository was asked to act on an argument this build had already refused");
    }

    @Test
    @DisplayName("a listing past the caller's own budget is refused rather than shortened")
    void alistingPastTheBudgetIsRefused() {
        assertEquals(PrincipalHandler.DISCOVERY_BUDGET_EXCEEDED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new PrincipalHandler(CONTRACT, PrincipalHandler.Kind.LISTING,
                                Directory::new, permissive())
                                .run(listing(GROUP, true), null,
                                        contextWith(new Budget(Budget.Kind.DISCOVERY, 1))),
                        "a listing past the caller's budget answered a shortened membership list,"
                                + " which reads as the complete answer").category());
        assertEquals(1, PrincipalHandler.pageOf(
                List.of(new PrincipalDirectory.Member(
                                new PrincipalDirectory.Principal(USER,
                                        PrincipalDirectory.Kind.USER, "/home/users/j/" + USER),
                                PrincipalDirectory.Membership.DIRECT),
                        new PrincipalDirectory.Member(
                                new PrincipalDirectory.Principal("editors",
                                        PrincipalDirectory.Kind.GROUP, "/home/groups/e/editors"),
                                PrincipalDirectory.Membership.INDIRECT)),
                new rs.slingshot.agent.command.ResultWindow.Initial(1, 1)).size());
    }

    @Test
    @DisplayName("all eight rows are the client's own and every handler declares exactly them")
    void alleightRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(PrincipalCommands.CREATE_USER_WIRE_NAME,
                        PrincipalHandler.creationCategories()),
                Map.entry(PrincipalCommands.CREATE_GROUP_WIRE_NAME,
                        PrincipalHandler.creationCategories()),
                Map.entry(PrincipalCommands.UPDATE_PROFILE_WIRE_NAME,
                        PrincipalHandler.profileCategories()),
                Map.entry(PrincipalCommands.SET_DISABLED_WIRE_NAME,
                        PrincipalHandler.accountCategories()),
                Map.entry(PrincipalCommands.DELETE_WIRE_NAME,
                        PrincipalHandler.removalCategories()),
                Map.entry(PrincipalCommands.ADD_MEMBER_WIRE_NAME,
                        PrincipalHandler.membershipCategories()),
                Map.entry(PrincipalCommands.REMOVE_MEMBER_WIRE_NAME,
                        PrincipalHandler.membershipCategories()),
                Map.entry(PrincipalCommands.LIST_MEMBERS_WIRE_NAME,
                        PrincipalHandler.listingCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
        assertTrue(PrincipalHandler.creationCategories().containsAll(List.of(
                        PrincipalHandler.categoryFor(PrincipalCommands.Refusal.IDENTIFIER_REJECTED),
                        PrincipalHandler.categoryFor(PrincipalCommands.Refusal.PLACE_REJECTED),
                        PrincipalHandler.categoryFor(
                                PrincipalCommands.Refusal.PROPERTIES_REJECTED))),
                "a creation refusal reaches a category this command's own row does not declare");
        assertEquals(RegistryRow.OperationKey.REFUSED,
                row(PrincipalCommands.LIST_MEMBERS_WIRE_NAME).operationKey());
        assertEquals(RegistryRow.OperationKey.REQUIRED,
                row(PrincipalCommands.ADD_MEMBER_WIRE_NAME).operationKey());
    }

    private static List<Map.Entry<PrincipalHandler.Kind, DocumentValue.Mapping>> attempts() {
        return List.of(
                Map.entry(PrincipalHandler.Kind.USER_CREATION, creation(USER)),
                Map.entry(PrincipalHandler.Kind.GROUP_CREATION, creation(GROUP)),
                Map.entry(PrincipalHandler.Kind.PROFILE, creation(USER)),
                Map.entry(PrincipalHandler.Kind.ACCOUNT, account(USER, true, "")),
                Map.entry(PrincipalHandler.Kind.REMOVAL, removal(USER, "user")),
                Map.entry(PrincipalHandler.Kind.GRANT, membership(GROUP, USER)),
                Map.entry(PrincipalHandler.Kind.WITHDRAWAL, membership(GROUP, USER)),
                Map.entry(PrincipalHandler.Kind.LISTING, listing(GROUP, false)));
    }

    /** A directory that remembers what it was asked and answers from a fixed repository. */
    private static final class Directory implements PrincipalDirectory {

        private final List<String> asked = new ArrayList<>();
        private final List<Refused> refusal;

        Directory() {
            this(List.of());
        }

        Directory(String category, String detail) {
            this(List.of(new Refused(category, detail)));
        }

        private Directory(List<Refused> refusal) {
            this.refusal = List.copyOf(refusal);
        }

        List<String> calls() {
            return List.copyOf(asked);
        }

        private Outcome held(String call, Outcome answer) {
            asked.add(call);
            return refusal.isEmpty() ? answer : refusal.getFirst();
        }

        @Override
        public Outcome make(CreationRequest request, ResourceResolver session) {
            return held("create", new Made(new Principal(request.authorizableIdentifier(),
                    request.kind(), "/home/users/j/" + request.authorizableIdentifier())));
        }

        @Override
        public Outcome applyProfile(String authorizableIdentifier,
                                     SequencedMap<String, PropertyValue> properties,
                                     SequencedSet<String> removedPropertyNames,
                                     ResourceResolver session) {
            return held("profile", new Changed("/home/users/j/" + authorizableIdentifier));
        }

        @Override
        public Outcome applyAccountState(String authorizableIdentifier, AccountState state,
                                         String reason, ResourceResolver session) {
            return held("account", new Changed("/home/users/j/" + authorizableIdentifier));
        }

        @Override
        public Outcome erase(String authorizableIdentifier, Kind expectedKind,
                             ResourceResolver session) {
            return held("delete", new Removed(new Principal(authorizableIdentifier, expectedKind,
                    "/home/users/j/" + authorizableIdentifier)));
        }

        @Override
        public Outcome applyMembership(String groupIdentifier, String memberIdentifier,
                                       MembershipChange change, ResourceResolver session) {
            return held("membership", new MembershipSettled(Settlement.CHANGED));
        }

        @Override
        public Outcome members(String groupIdentifier, Reach reach, ResourceResolver session) {
            return held("members", new Members(List.of(
                    new Member(new Principal(USER, Kind.USER, "/home/users/j/" + USER),
                            Membership.DIRECT),
                    new Member(new Principal("editors", Kind.GROUP, "/home/groups/e/editors"),
                            Membership.INDIRECT))));
        }
    }

    private static CommandHandler.Answer run(PrincipalHandler.Kind kind,
                                             DocumentValue.Mapping arguments) {
        return new PrincipalHandler(CONTRACT, kind, Directory::new, permissive())
                .run(arguments, null, context());
    }

    private static PlatformControl permissive() {
        return PlatformControl.of("aem-6-5-lts", Set.of(ControlCapability.values()));
    }

    private static DocumentValue.Mapping creation(String identifier) {
        final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
        properties.put("profile/givenName", single("Jane"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(identifier));
        members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(properties));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue single(String value) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        final SequencedMap<String, DocumentValue> scalar = new LinkedHashMap<>();
        scalar.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        scalar.put(PropertyScalar.VALUE, new DocumentValue.Text(value));
        held.put(PropertyValue.VALUE, new DocumentValue.Mapping(scalar));
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue.Mapping identifierNamed(String member, String value) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(member, new DocumentValue.Text(value));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping removal(String identifier, String kind) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(identifier));
        members.put(PrincipalCommands.EXPECTED_KIND, new DocumentValue.Text(kind));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping account(String identifier, boolean disabled,
                                                 String reason) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.AUTHORIZABLE_IDENTIFIER, new DocumentValue.Text(identifier));
        members.put(PrincipalCommands.DISABLED, new DocumentValue.Flag(
                disabled ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        if (!reason.isEmpty()) {
            members.put(PrincipalCommands.REASON, new DocumentValue.Text(reason));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping membership(String group, String member) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.GROUP_IDENTIFIER, new DocumentValue.Text(group));
        members.put(PrincipalCommands.MEMBER_IDENTIFIER, new DocumentValue.Text(member));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping listing(String group, boolean indirect) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(PrincipalCommands.GROUP_IDENTIFIER, new DocumentValue.Text(group));
        members.put(PrincipalCommands.INCLUDE_INDIRECT, new DocumentValue.Flag(
                indirect ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(members);
    }

    private static CallerContext context() {
        return contextWith(Budget.discovery(CONTRACT));
    }

    private static CallerContext contextWith(Budget discovery) {
        return new CallerContext(operation(), discovery, Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row(String wire) {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry().row(wire).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
