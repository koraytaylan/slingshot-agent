// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.SequencedSet;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;

/**
 * The users-and-groups adapter, driven against a real Oak repository.
 *
 * <p>A fake user manager would prove only that this class calls the methods it calls. What matters
 * is what the repository then holds: that a user made here has no password at all, that a group
 * with members is refused rather than emptied, that a membership cycle is refused before anything
 * changes, and that every refusal is spelled in a category its command declares.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class DefaultPrincipalDirectoryTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private final PrincipalDirectory directory = DefaultPrincipalDirectory.directories().open();

    @Test
    @DisplayName("a user is made with no password at all and its typed properties")
    void auserIsMadeWithNoPassword() throws RepositoryException {
        final PrincipalDirectory.Made made = assertInstanceOf(PrincipalDirectory.Made.class,
                directory.make(user("jane", List.of(new PrincipalDirectory.NamedValue(
                        "profile/age", single(ScalarKind.INTEGER, "41")))), resolver()));
        assertEquals("jane", made.principal().authorizableIdentifier());
        assertEquals(PrincipalDirectory.Kind.USER, made.principal().kind());
        final Authorizable held = Objects.requireNonNull(users().getAuthorizable("jane"),
                "the user was not committed");
        assertFalse(held.isGroup(), "a user was made as a group");
        assertFalse(session().getNode(held.getPath()).hasProperty("rep:password"),
                "a user made through a command carries a password");
        assertEquals(PropertyType.LONG, held.getProperty("profile/age")[0].getType(),
                "an integer was stored as something other than an integer");
        assertEquals(PrincipalDirectory.Refused.class, directory.make(user("jane", List.of()),
                resolver()).getClass(), "a second user of the same name was made");
        assertEquals("authorizable_already_exists", ((PrincipalDirectory.Refused) directory.make(
                user("jane", List.of()), resolver())).category());
    }

    @Test
    @DisplayName("a group is made where it was asked to be, or where the repository puts groups")
    void agroupIsMadeInItsPlace() throws RepositoryException {
        assertInstanceOf(PrincipalDirectory.Made.class, directory.make(
                new PrincipalDirectory.CreationRequest("editors", PrincipalDirectory.Kind.GROUP,
                        "", List.of()), resolver()));
        final PrincipalDirectory.Made placed = assertInstanceOf(PrincipalDirectory.Made.class,
                directory.make(new PrincipalDirectory.CreationRequest("reviewers",
                        PrincipalDirectory.Kind.GROUP, "acme", List.of()), resolver()));
        assertTrue(placed.principal().repositoryPath().contains("/acme/"),
                "a group asked to be placed was put somewhere else: "
                        + placed.principal().repositoryPath());
        final PrincipalDirectory.Made placedUser = assertInstanceOf(PrincipalDirectory.Made.class,
                directory.make(new PrincipalDirectory.CreationRequest("sam",
                        PrincipalDirectory.Kind.USER, "acme", List.of()), resolver()));
        assertTrue(placedUser.principal().repositoryPath().contains("/acme/"));
        assertTrue(Objects.requireNonNull(users().getAuthorizable("editors"),
                "the group was not committed").isGroup(), "a group was made as a user");
    }

    @Test
    @DisplayName("a profile change writes, removes, and refuses a property that is not there")
    void aprofileChangeWritesAndRemoves() throws RepositoryException {
        directory.make(user("jane", List.of(new PrincipalDirectory.NamedValue("profile/city",
                single(ScalarKind.STRING, "Rome")))), resolver());
        final SequencedMap<String, PropertyValue> set = new LinkedHashMap<>();
        set.put("profile/givenName", single(ScalarKind.STRING, "Jane"));
        final SequencedSet<String> removed = new LinkedHashSet<>(List.of("profile/city"));
        assertInstanceOf(PrincipalDirectory.Changed.class,
                directory.applyProfile("jane", set, removed, resolver()));
        final Authorizable held = users().getAuthorizable("jane");
        assertEquals("Jane", held.getProperty("profile/givenName")[0].getString());
        assertFalse(held.hasProperty("profile/city"), "a removed property is still there");
        assertEquals("property_not_removable", refusal(directory.applyProfile("jane",
                new LinkedHashMap<>(), new LinkedHashSet<>(List.of("profile/none")),
                resolver())));
        assertEquals("authorizable_not_found", refusal(directory.applyProfile("nobody",
                new LinkedHashMap<>(), new LinkedHashSet<>(), resolver())));
        directory.make(new PrincipalDirectory.CreationRequest("editors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        assertEquals("authorizable_kind_mismatch", refusal(directory.applyProfile("editors",
                new LinkedHashMap<>(), new LinkedHashSet<>(), resolver())));
    }

    @Test
    @DisplayName("an account is turned off with its reason and on again")
    void anaccountIsTurnedOffAndOn() throws RepositoryException {
        directory.make(user("jane", List.of()), resolver());
        assertInstanceOf(PrincipalDirectory.Changed.class, directory.applyAccountState("jane",
                AccountState.DISABLED, "left the company", resolver()));
        final User off = (User) users().getAuthorizable("jane");
        assertTrue(off.isDisabled());
        assertEquals("left the company", off.getDisabledReason());
        assertInstanceOf(PrincipalDirectory.Changed.class, directory.applyAccountState("jane",
                AccountState.ENABLED, "", resolver()));
        assertFalse(((User) users().getAuthorizable("jane")).isDisabled());
        directory.applyAccountState("jane", AccountState.DISABLED, "", resolver());
        assertTrue(((User) users().getAuthorizable("jane")).getDisabledReason().length() > 0,
                "a user turned off with no reason was left with none to read");
        assertEquals("authorizable_not_found", refusal(directory.applyAccountState("nobody",
                AccountState.DISABLED, "", resolver())));
        assertEquals("platform_control_rejected", refusal(directory.applyAccountState("admin",
                AccountState.DISABLED, "", resolver())),
                "the administrator was turned off, which locks the platform out of itself");
    }

    @Test
    @DisplayName("a removal checks the kind, and refuses a group that still has members")
    void aremovalChecksTheKindAndTheMembers() throws RepositoryException {
        directory.make(user("jane", List.of()), resolver());
        directory.make(new PrincipalDirectory.CreationRequest("editors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        directory.applyMembership("editors", "jane", PrincipalDirectory.MembershipChange.GRANTED,
                resolver());
        assertEquals("authorizable_kind_mismatch", refusal(directory.erase("editors",
                PrincipalDirectory.Kind.USER, resolver())));
        assertEquals("group_has_members", refusal(directory.erase("editors",
                PrincipalDirectory.Kind.GROUP, resolver())));
        assertEquals("authorizable_not_found", refusal(directory.erase("nobody",
                PrincipalDirectory.Kind.USER, resolver())));
        assertInstanceOf(PrincipalDirectory.Removed.class,
                directory.erase("jane", PrincipalDirectory.Kind.USER, resolver()));
        assertEquals(null, users().getAuthorizable("jane"), "a removed user is still there");
        assertInstanceOf(PrincipalDirectory.Removed.class,
                directory.erase("editors", PrincipalDirectory.Kind.GROUP, resolver()));
    }

    @Test
    @DisplayName("a membership is granted once, reported as already there, withdrawn, and never cycles")
    void amembershipIsSettledAndNeverCycles() {
        directory.make(user("jane", List.of()), resolver());
        directory.make(new PrincipalDirectory.CreationRequest("editors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        directory.make(new PrincipalDirectory.CreationRequest("authors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        assertEquals(PrincipalDirectory.Settlement.CHANGED, settled(directory.applyMembership(
                "editors", "jane", PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals(PrincipalDirectory.Settlement.ALREADY_AS_ASKED, settled(
                directory.applyMembership("editors", "jane",
                        PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals(PrincipalDirectory.Settlement.CHANGED, settled(directory.applyMembership(
                "authors", "editors", PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals("membership_cycle_refused", refusal(directory.applyMembership("editors",
                "authors", PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals("membership_cycle_refused", refusal(directory.applyMembership("editors",
                "editors", PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals("group_not_found", refusal(directory.applyMembership("nobody", "jane",
                PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals("authorizable_kind_mismatch", refusal(directory.applyMembership("jane",
                "editors", PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals("member_not_found", refusal(directory.applyMembership("editors", "nobody",
                PrincipalDirectory.MembershipChange.GRANTED, resolver())));
        assertEquals(PrincipalDirectory.Settlement.CHANGED, settled(directory.applyMembership(
                "editors", "jane", PrincipalDirectory.MembershipChange.WITHDRAWN, resolver())));
        assertEquals(PrincipalDirectory.Settlement.ALREADY_AS_ASKED, settled(
                directory.applyMembership("editors", "jane",
                        PrincipalDirectory.MembershipChange.WITHDRAWN, resolver())));
    }

    @Test
    @DisplayName("a listing tells a direct member from one held through another group")
    void alistingTellsDirectFromIndirect() {
        directory.make(user("jane", List.of()), resolver());
        directory.make(new PrincipalDirectory.CreationRequest("editors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        directory.make(new PrincipalDirectory.CreationRequest("authors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        directory.applyMembership("editors", "jane", PrincipalDirectory.MembershipChange.GRANTED,
                resolver());
        directory.applyMembership("authors", "editors",
                PrincipalDirectory.MembershipChange.GRANTED, resolver());
        final List<PrincipalDirectory.Member> direct = assertInstanceOf(
                PrincipalDirectory.Members.class, directory.members("authors",
                        PrincipalDirectory.Reach.DIRECT_ONLY, resolver())).members();
        assertEquals(List.of("editors"), direct.stream()
                .map(member -> member.principal().authorizableIdentifier()).toList());
        final List<PrincipalDirectory.Member> every = assertInstanceOf(
                PrincipalDirectory.Members.class, directory.members("authors",
                        PrincipalDirectory.Reach.INCLUDING_INDIRECT, resolver())).members();
        assertEquals(PrincipalDirectory.Membership.INDIRECT, every.stream()
                .filter(member -> "jane".equals(member.principal().authorizableIdentifier()))
                .findFirst().orElseThrow().membership());
        assertEquals("group_not_found", refusal(directory.members("nobody",
                PrincipalDirectory.Reach.DIRECT_ONLY, resolver())));
        assertEquals("authorizable_kind_mismatch", refusal(directory.members("jane",
                PrincipalDirectory.Reach.DIRECT_ONLY, resolver())));
    }

    @Test
    @DisplayName("every kind of value keeps its kind, a list stays a list, and a bad value is refused")
    void everyKindOfValueKeepsItsKind() throws RepositoryException {
        assertInstanceOf(PrincipalDirectory.Made.class, directory.make(user("jane", List.of(
                new PrincipalDirectory.NamedValue("profile/active",
                        single(ScalarKind.BOOLEAN, "true")),
                new PrincipalDirectory.NamedValue("profile/ratio",
                        single(ScalarKind.DECIMAL, "1.5")),
                new PrincipalDirectory.NamedValue("profile/joined",
                        single(ScalarKind.DATE_TIME, "2026-01-02T03:04:05.000Z")),
                new PrincipalDirectory.NamedValue("profile/home",
                        single(ScalarKind.REPOSITORY_PATH, "/content/site")),
                new PrincipalDirectory.NamedValue("profile/aliases", new PropertyValue.Multiple(
                        List.of(new PropertyScalar(ScalarKind.STRING, "j"),
                                new PropertyScalar(ScalarKind.STRING, "jd")))))), resolver()));
        final Authorizable held = users().getAuthorizable("jane");
        assertEquals(PropertyType.BOOLEAN, held.getProperty("profile/active")[0].getType());
        assertEquals(PropertyType.DECIMAL, held.getProperty("profile/ratio")[0].getType());
        assertEquals(PropertyType.DATE, held.getProperty("profile/joined")[0].getType());
        assertEquals(PropertyType.PATH, held.getProperty("profile/home")[0].getType());
        assertEquals(2, held.getProperty("profile/aliases").length);
        assertEquals("property_rejected", refusal(directory.make(user("sam", List.of(
                new PrincipalDirectory.NamedValue("profile/age",
                        single(ScalarKind.INTEGER, "not a number")))), resolver())));
        assertEquals(null, users().getAuthorizable("sam"),
                "a user whose property was refused was made anyway");
    }

    @Test
    @DisplayName("an identifier or a place the repository will not take is refused as such")
    void anidentifierOrPlaceTheRepositoryRefusesIsRefused() {
        assertEquals("identifier_rejected", refusal(directory.make(user("", List.of()),
                resolver())));
        assertEquals("intermediate_path_rejected", refusal(directory.make(
                new PrincipalDirectory.CreationRequest("jane", PrincipalDirectory.Kind.USER,
                        "/content/elsewhere", List.of()), resolver())));
    }

    @Test
    @DisplayName("a caller with no repository session is refused by every call, and nothing changes")
    void acallerWithNoSessionIsRefused() {
        final List<PrincipalDirectory.Outcome> refused;
        try (ResourceResolver sessionless = new SlingContext(
                ResourceResolverType.RESOURCERESOLVER_MOCK).resourceResolver()) {
            refused = List.of(
                directory.make(user("jane", List.of()), sessionless),
                directory.applyProfile("jane", new LinkedHashMap<>(), new LinkedHashSet<>(),
                        sessionless),
                directory.applyAccountState("jane", AccountState.DISABLED, "", sessionless),
                directory.erase("jane", PrincipalDirectory.Kind.USER, sessionless),
                directory.applyMembership("editors", "jane",
                        PrincipalDirectory.MembershipChange.GRANTED, sessionless),
                directory.members("editors", PrincipalDirectory.Reach.DIRECT_ONLY, sessionless));
        }
        refused.forEach(outcome -> assertEquals("authorizable_access_denied", refusal(outcome)));
    }

    @Test
    @DisplayName("a caller the repository denies is refused as the repository denied them")
    void acallerTheRepositoryDeniesIsRefused()
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        directory.make(user("jane", List.of()), resolver());
        directory.make(user("sam", List.of()), resolver());
        directory.make(new PrincipalDirectory.CreationRequest("editors",
                PrincipalDirectory.Kind.GROUP, "", List.of()), resolver());
        // An account of the suite's own with a password, which no command could have made: the
        // only way to be a caller the repository refuses is to sign in as one.
        users().createUser("reader", "reader-password");
        session().save();
        final Session limited = session().getRepository().login(
                new javax.jcr.SimpleCredentials("reader", "reader-password".toCharArray()));
        try (ResourceResolver asJane = Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class),
                "the context has no resolver factory").getResourceResolver(
                        java.util.Map.of("user.jcr.session", limited))) {
            refusedAsJane(asJane);
        }
        final org.apache.jackrabbit.api.security.user.Group editors = Objects.requireNonNull(
                (org.apache.jackrabbit.api.security.user.Group) users().getAuthorizable("editors"),
                "the group is gone");
        assertFalse(editors.isDeclaredMember(Objects.requireNonNull(
                users().getAuthorizable("jane"), "the user is gone")),
                "a refused membership was committed anyway");
    }

    private void refusedAsJane(ResourceResolver asJane) {
        assertEquals("authorizable_access_denied", refusal(directory.make(user("eve", List.of()),
                asJane)), "an unprivileged caller made a user");
        final SequencedMap<String, PropertyValue> set = new LinkedHashMap<>();
        set.put("profile/givenName", single(ScalarKind.STRING, "Sam"));
        assertTrue(directory.applyProfile("sam", set, new LinkedHashSet<>(), asJane)
                        instanceof PrincipalDirectory.Refused,
                "an unprivileged caller changed somebody else's profile");
        assertTrue(directory.applyAccountState("sam", AccountState.DISABLED, "", asJane)
                        instanceof PrincipalDirectory.Refused,
                "an unprivileged caller turned somebody else off");
        assertTrue(directory.erase("sam", PrincipalDirectory.Kind.USER, asJane)
                        instanceof PrincipalDirectory.Refused,
                "an unprivileged caller removed somebody");
        assertTrue(directory.applyMembership("editors", "jane",
                        PrincipalDirectory.MembershipChange.GRANTED, asJane)
                        instanceof PrincipalDirectory.Refused,
                "an unprivileged caller granted themselves a group");
        assertInstanceOf(PrincipalDirectory.Refused.class, directory.members("editors",
                PrincipalDirectory.Reach.DIRECT_ONLY, asJane));
    }

    @Test
    @DisplayName("a change the repository does not commit is reported so and taken back")
    void achangeNotCommittedIsReportedAndTakenBack() throws RepositoryException {
        try (ResourceResolver failing = committingFails(new RepositoryException("injected"))) {
            notCommitted(failing);
        }
        try (ResourceResolver denying = committingFails(
                new javax.jcr.AccessDeniedException("injected"))) {
            assertEquals("authorizable_access_denied", refusal(directory.erase("sam",
                    PrincipalDirectory.Kind.USER, denying)));
        }
    }

    private void notCommitted(ResourceResolver failing) throws RepositoryException {
        assertEquals("repository_commit_failed", refusal(directory.make(user("jane", List.of()),
                failing)));
        assertEquals(null, users().getAuthorizable("jane"),
                "a user whose commit failed was left in the session");
        directory.make(user("sam", List.of()), resolver());
        assertEquals("platform_control_rejected", refusal(directory.applyAccountState("sam",
                AccountState.DISABLED, "", failing)),
                "an account change that did not commit was reported in another command's words");
        assertFalse(((User) users().getAuthorizable("sam")).isDisabled(),
                "an account change whose commit failed was left in the session");
    }

    /**
     * The caller's own resolver, whose session refuses every commit with one failure.
     *
     * <p>Everything else is the real repository, so what was changed before the commit is real
     * and taking it back is observable.</p>
     */
    private ResourceResolver committingFails(RepositoryException failure) {
        final Session real = session();
        final Session failing = (Session) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {JackrabbitSession.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        throw failure;
                    }
                    try {
                        return method.invoke(real, arguments);
                    } catch (final java.lang.reflect.InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
        return (ResourceResolver) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {ResourceResolver.class}, (proxy, method, arguments) -> {
                    if ("close".equals(method.getName())) {
                        // The context's own resolver is the context's to close, not this one's.
                        return null;
                    }
                    return "adaptTo".equals(method.getName()) && Session.class.equals(arguments[0])
                            ? failing : method.invoke(resolver(), arguments);
                });
    }

    private static PrincipalDirectory.CreationRequest user(String identifier,
                                                           List<PrincipalDirectory.NamedValue>
                                                                   properties) {
        return new PrincipalDirectory.CreationRequest(identifier, PrincipalDirectory.Kind.USER,
                "", properties);
    }

    private static PropertyValue single(ScalarKind kind, String value) {
        return new PropertyValue.Single(new PropertyScalar(kind, value));
    }

    private static String refusal(PrincipalDirectory.Outcome outcome) {
        return assertInstanceOf(PrincipalDirectory.Refused.class, outcome).category();
    }

    private static PrincipalDirectory.Settlement settled(PrincipalDirectory.Outcome outcome) {
        return assertInstanceOf(PrincipalDirectory.MembershipSettled.class, outcome)
                .settlement();
    }

    private ResourceResolver resolver() {
        return sling.resourceResolver();
    }

    private Session session() {
        return Objects.requireNonNull(resolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
    }

    private UserManager users() throws RepositoryException {
        return ((JackrabbitSession) session()).getUserManager();
    }
}
