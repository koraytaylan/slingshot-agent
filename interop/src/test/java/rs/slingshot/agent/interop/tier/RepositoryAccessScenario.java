// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * What the agent's own identity may do, read back off a repository that created it.
 *
 * <p>The declared grants and the created ones are compared in both directions on a running
 * instance, out of the same two files a deployment installs: the committed policy says what the
 * service user may do, the committed configuration is what creates it, and neither is trusted to
 * describe the other. The three paths the policy refuses are asserted to hold nothing for that
 * principal, because default-deny is what makes "cannot write content" true, and an entry
 * somewhere granting it would be the one way that stops being so.</p>
 *
 * <p>This is the property the whole access argument rests on: the agent's bookkeeping is the
 * agent's, and everything a caller asked for runs as the caller. A service user that could reach
 * content, applications, or the user tree would make the second half of that sentence a promise
 * rather than a consequence.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RepositoryAccessScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The committed policy, which is where the declared grants come from. */
    private static final String POLICY = "policy/repository-access.toml";

    /** The committed configuration a deployment installs, which is what creates them. */
    private static final String CONFIGURATION =
            "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config";

    /** Where the platform's own installer reads configuration out of the repository. */
    private static final String CONFIGURATION_TREE = "/apps/slingshot-agent/osgiconfig/config";

    /** The tree the agent writes, which the configuration creates and the policy grants on. */
    private static final String AGENT_TREE = "/var/slingshot-agent";

    /** How many times the tree is asked for before the configuration is called uninstalled. */
    private static final int INSTALL_ATTEMPTS = 120;

    /** How long between those asks. */
    private static final int INSTALL_POLL_MILLISECONDS = 1000;

    /** What the platform answers for something that is there. */
    private static final int FOUND = 200;

    /**
     * Oak's own aggregate, which is what a repository reports where a script granted the two
     * privileges it stands for. Comparing the reported set with the declared one without expanding
     * it would report a difference between two spellings of the same grant.
     */
    private static final String AGGREGATE = "rep:write";

    private static final List<String> AGGREGATED =
            List.of("jcr:write", "jcr:nodeTypeManagement");

    private static final Pattern PRIVILEGES =
            Pattern.compile("\"rep:privileges\":\\[([^]]*)]");

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private PublicSlingTier tier;

    private TomlParseResult policy;

    @BeforeAll
    void installTheConfigurationADeploymentInstalls() {
        policy = parsed(REPOSITORY.resolve(POLICY));
        // Its own runtime, because this scenario changes the instance itself. The shared one is
        // given back first: two published runtimes competing for the machine is how a start that
        // takes twenty seconds stops finishing inside ninety.
        SharedPublicSlingTier.release();
        final InteropTier.Outcome outcome =
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = (PublicSlingTier) assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
        hand();
        assertTrue(installed(), "the configuration was handed over and " + AGENT_TREE
                + " never appeared, so the platform's installer did not run it");
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("the service user the policy declares is the one the repository holds")
    void theRepositoryHoldsTheDeclaredServiceUser() {
        final String home = text("service_user.home");
        final HttpResponse<String> read = tier.readAsAuthenticatedUser(home + ".1.json");
        assertEquals(FOUND, read.statusCode(), home + " holds no service user: " + read.body());
        assertTrue(read.body().contains("\"rep:principalName\":\"" + principal() + "\""),
                home + " holds somebody else: " + read.body());
        assertTrue(read.body().contains("\"jcr:primaryType\":\"rep:SystemUser\""),
                "the agent's identity is not a system user, so something could log in as it: "
                        + read.body());
    }

    @Test
    @DisplayName("the declared grants and the ones the repository created are the same set")
    void theDeclaredAndCreatedGrantsAgree() {
        final String reported = policyAt(AGENT_TREE).body();
        assertEquals(declaredPrivileges(), reportedPrivileges(reported),
                "the policy and the repository disagree about what the agent may do: " + reported);
        assertEquals(1, occurrences(reported, "rep:principalName"),
                "the tree carries an entry for somebody other than the agent: " + reported);
        assertTrue(reported.contains("\"rep:principalName\":\"" + principal() + "\""),
                "the one entry on the agent's tree is not the agent's: " + reported);
    }

    @Test
    @DisplayName("the agent's identity holds nothing at any path the policy refuses")
    void theRefusedPathsHoldNothingForTheAgent() {
        final List<String> refused = rows("refused_path").stream()
                .map(row -> String.valueOf(row.getString("path")))
                .toList();
        assertEquals(3, refused.size(), "the policy stopped refusing a path: " + refused);
        refused.forEach(path -> {
            assertEquals(FOUND, tier.readAsAuthenticatedUser(path + ".json").statusCode(),
                    path + " is not on this instance, so nothing was proved about it");
            assertFalse(policyAt(path).body().contains(principal()),
                    principal() + " holds an entry on " + path + ", which is the one way the"
                            + " agent becomes a way to do what a caller could not do themselves");
        });
    }

    @Test
    @DisplayName("handing the same configuration over again leaves the same grants")
    void handingTheConfigurationOverAgainChangesNothing() {
        final String before = reportedPrivileges(policyAt(AGENT_TREE).body()).toString();
        hand();
        assertTrue(installed(), AGENT_TREE + " went away when the configuration was reinstalled");
        assertEquals(before, reportedPrivileges(policyAt(AGENT_TREE).body()).toString(),
                "reinstalling the same configuration changed what the agent may do");
    }

    private void hand() {
        final List<String> folders = List.of("/apps/slingshot-agent",
                "/apps/slingshot-agent/osgiconfig", CONFIGURATION_TREE);
        folders.forEach(folder -> {
            final int answered =
                    tier.submit(folder, List.of("jcr:primaryType", "sling:Folder")).statusCode();
            assertTrue(answered < 400, folder + " was refused with " + answered);
        });
        committedConfiguration().forEach(file -> {
            final HttpResponse<String> handed = tier.upload(CONFIGURATION_TREE + "/",
                    "./" + file.getFileName(), file);
            assertTrue(handed.statusCode() < 400,
                    file.getFileName() + " was refused with " + handed.statusCode());
        });
    }

    private boolean installed() {
        return IntStream.range(0, INSTALL_ATTEMPTS)
                .peek(attempt -> pause(attempt))
                .anyMatch(attempt ->
                        tier.readAsAuthenticatedUser(AGENT_TREE + ".json").statusCode() == FOUND);
    }

    private static void pause(int attempt) {
        if (attempt == 0) {
            return;
        }
        try {
            Thread.sleep(INSTALL_POLL_MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpResponse<String> policyAt(String path) {
        return tier.readAsAuthenticatedUser(path + "/rep:policy.-1.json");
    }

    private String principal() {
        return text("service_user.name");
    }

    private String text(String key) {
        return java.util.Optional.ofNullable(policy.getString(key))
                .orElseThrow(() -> new IllegalStateException(POLICY + " declares no " + key));
    }

    private Set<String> declaredPrivileges() {
        return rows("grant").stream()
                .filter(row -> AGENT_TREE.equals(row.getString("path")))
                .flatMap(row -> privilegesOf(row).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> privilegesOf(TomlTable row) {
        final TomlArray declared = java.util.Optional.ofNullable(row.getArray("privileges"))
                .orElseThrow(() -> new IllegalStateException("a grant declares no privileges"));
        return IntStream.range(0, declared.size())
                .mapToObj(declared::getString)
                .toList();
    }

    private static Set<String> reportedPrivileges(String body) {
        final Set<String> reported = new LinkedHashSet<>();
        final Matcher arrays = PRIVILEGES.matcher(body);
        while (arrays.find()) {
            final Matcher named = QUOTED.matcher(arrays.group(1));
            while (named.find()) {
                reported.addAll(AGGREGATE.equals(named.group(1))
                        ? AGGREGATED
                        : List.of(named.group(1)));
            }
        }
        return reported;
    }

    private List<TomlTable> rows(String name) {
        final TomlArray declared = java.util.Optional.ofNullable(policy.getArray(name))
                .orElseThrow(() -> new IllegalStateException(POLICY + " declares no " + name));
        final List<TomlTable> rows = new ArrayList<>();
        IntStream.range(0, declared.size()).forEach(index -> rows.add(declared.getTable(index)));
        return rows;
    }

    private static long occurrences(String body, String token) {
        return body.split(Pattern.quote(token), -1).length - 1L;
    }

    private static List<Path> committedConfiguration() {
        try (var files = Files.list(REPOSITORY.resolve(CONFIGURATION))) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".cfg.json"))
                    .sorted()
                    .toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static TomlParseResult parsed(Path document) {
        try {
            final TomlParseResult read = Toml.parse(document);
            assertTrue(read.errors().isEmpty(), document + " does not parse: " + read.errors());
            return read;
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path builtBundle() {
        try (var files = Files.list(REPOSITORY.resolve("core/target"))) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built; run the reactor build first"));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
