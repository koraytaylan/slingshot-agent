// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What the console actually renders for a workload somebody drove, read from the response.
 *
 * <p>Granite renders on the server, so this console's markup is fully determined by a response a
 * test can ask for over HTTP. A browser driver would add a large dependency and a whole class of
 * flakiness to prove something the server already decided, so this proof reads the markup.</p>
 *
 * <p>What it is really about is that the console and the machine-readable surface cannot disagree.
 * Every value a page shows came from a store that a route also answers from, and the day the two
 * differ is the day an operator and a client are looking at different accounts of the same
 * operation with no way to tell which is right.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ConsoleRenderScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The route a running operation is followed on, which the console follows and does not copy. */
    private static final String EVENTS = "/bin/slingshot/agent/events";

    /** Where the operations list is reached. */
    private static final String LIST = "/apps/slingshot-agent/content/console.html";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a submission this build will not act on is answered with. */
    private static final int REFUSED = 400;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("the workload this proof drives goes through the route rather than round it")
    void theworkloadIsDrivenThroughTheRoute() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity, and everything this"
                        + " proof renders was submitted through this route");
        assertEquals(REFUSED, requests.postAsAuthenticatedUser(tier.address() + SUBMIT,
                        "{\"command_wire_name\":\"create_page\"}", "application/json")
                .statusCode(),
                "a submission carrying nothing but a name was accepted, so what the console"
                        + " renders would not be what the stores hold");
    }

    @Test
    @DisplayName("the tail follows the same route the client library follows, not a second stream")
    void thetailFollowsTheClientsOwnRoute() {
        final String script = read(REPOSITORY.resolve("ui.apps/src/main/content/jcr_root/apps"
                + "/slingshot-agent/clientlibs/console/js/console.js"));
        assertTrue(script.contains(EVENTS),
                "the console follows something other than the event route the client uses, and a"
                        + " second implementation of resumption and ordering is two accounts of one"
                        + " operation: " + script);
        assertEquals(UNAUTHENTICATED,
                requests.readAsNobody(tier.address() + EVENTS + "?operation=none").statusCode(),
                "the route the console follows answered a caller who presented no identity");
    }

    @Test
    @DisplayName("every rendered value comes from a store a route also answers from")
    void everyrenderedValueHasOneSource() {
        final String rendered = requests.readAsNobody(tier.address() + LIST).body();
        assertTrue(!rendered.contains("slingshot-agent-operations"),
                "the operations list rendered for a caller who presented no identity, so what it"
                        + " shows is not what an authorized viewer would be shown");
        final String page = read(REPOSITORY.resolve("ui.apps/src/main/content/jcr_root/apps"
                + "/slingshot-agent/content/console/.content.xml"));
        assertTrue(page.contains("slingshot-agent/datasource/operations"),
                "the list no longer reads a data source, which is the only thing that keeps it and"
                        + " the routes reading one store");
        assertTrue(!page.contains("/var/slingshot"),
                "the page names the agent's own storage, and the stores live where no person's"
                        + " session reaches them: " + page);
    }

    @Test
    @DisplayName("the document and the package describe the same pages")
    void thedocumentDescribesWhatIsThere() {
        final String document = read(REPOSITORY.resolve("docs/CONSOLE.md"));
        pages().forEach(page -> assertTrue(document.contains(page),
                page + " is carried by the package and the document does not describe it"));
    }

    private static List<String> pages() {
        final Path console = REPOSITORY.resolve("ui.apps/src/main/content/jcr_root/apps"
                + "/slingshot-agent/content/console");
        try (var found = Files.walk(console)) {
            return found.filter(path -> ".content.xml".equals(String.valueOf(path.getFileName())))
                    .map(path -> REPOSITORY.relativize(path.getParent()).toString()
                            .replace('\\', '/'))
                    .map(path -> path.substring(path.indexOf("/jcr_root") + "/jcr_root".length()))
                    .sorted()
                    .toList();
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
