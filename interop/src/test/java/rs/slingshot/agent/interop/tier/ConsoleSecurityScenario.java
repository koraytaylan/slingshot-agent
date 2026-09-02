// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * The console's whole reachable surface, driven the way somebody would drive it to get past it.
 *
 * <p>A data source is a servlet like any other, and this is the proof that a running instance
 * treats it like one. The surface is derived from the built package rather than listed here, so a
 * page somebody adds next year is covered the day it is added — which is the only version of this
 * proof that stays true.</p>
 *
 * <p>Every page is asked for as nobody at all, and asked for again at every alternative spelling
 * Sling's own resolution would accept. A path with a selector, an extension, or a suffix reaches the
 * same resource, and a check that only asked for the canonical spelling would be a check that
 * passed while the console was wide open.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ConsoleSecurityScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** Where the console's own resources sit inside the content package. */
    private static final String CONSOLE = "ui.apps/src/main/content/jcr_root/apps/slingshot-agent"
            + "/content/console";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a request this build will not answer at all is answered below. */
    private static final int BELOW_A_SUCCESS = 300;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "the tier left a container running");
    }

    @Test
    @DisplayName("the surface is derived from the package, so a page added later is covered anyway")
    void thesurfaceIsDerivedFromThePackage() {
        assertTrue(pages().size() > 1,
                "the console has one page or none, and this proof would then be proving nothing: "
                        + pages());
        assertTrue(pages().stream()
                        .allMatch(page -> page.startsWith("/apps/slingshot-agent/content/console")),
                "a console page sits somewhere the console's own authority does not guard: "
                        + pages());
    }

    @Test
    @DisplayName("every console page refuses a caller who authenticated as nobody")
    void everypageRefusesNobody() {
        pages().forEach(page -> assertEquals(UNAUTHENTICATED,
                requests.readAsNobody(tier.address() + page + ".html").statusCode(),
                page + " was rendered for a caller who presented no identity"));
    }

    @Test
    @DisplayName("no alternative spelling of a page reaches past the authority")
    void noalternativeSpellingGetsPast() {
        pages().forEach(page -> spellingsOf(page).forEach(spelling -> {
            final HttpResponse<String> answered =
                    requests.readAsNobody(tier.address() + spelling);
            assertTrue(answered.statusCode() >= BELOW_A_SUCCESS,
                    spelling + " was answered for a caller who presented no identity: "
                            + answered.statusCode());
            assertTrue(!answered.body().contains("slingshot-agent-operations"),
                    spelling + " disclosed a console it should not have rendered");
        }));
    }

    @Test
    @DisplayName("no console resource declares anything that would change something")
    void nothingHereWrites() {
        final List<String> writes = new ArrayList<>();
        pages().forEach(page -> {
            final String held = read(REPOSITORY.resolve(CONSOLE
                    + page.substring(page.lastIndexOf("console") + "console".length())
                    + "/.content.xml"));
            List.of("/form", "/submit", "post", "delete", "/upload").stream()
                    .filter(held::contains)
                    .forEach(form -> writes.add(page + " declares " + form));
        });
        assertEquals(List.of(), writes,
                "a console resource would change something, which makes this a second way to do"
                        + " what the routes do with a second authorization story: " + writes);
    }

    @Test
    @DisplayName("no page discloses a value the redaction corpus plants, in any state")
    void nopageDisclosesACorpusValue() {
        final List<String> planted = plantedValues();
        assertTrue(!planted.isEmpty(),
                "the corpus plants nothing, so this proof would pass on a console that disclosed"
                        + " everything");
        final List<String> disclosed = new ArrayList<>();
        pages().forEach(page -> {
            final String body = requests.readAsNobody(tier.address() + page + ".html").body();
            planted.stream()
                    .filter(body::contains)
                    .forEach(value -> disclosed.add(page + " disclosed the planted " + value));
        });
        assertEquals(List.of(), disclosed,
                "a console page carried a value the corpus plants, and an empty or broken state is"
                        + " where one gets through: " + disclosed);
    }

    /**
     * Every alternative spelling of one page Sling's own resolution would accept.
     *
     * @param page the canonical address
     * @return the spellings, each of which reaches the same resource
     */
    private static List<String> spellingsOf(String page) {
        return List.of(page + ".json", page + ".detail.html", page + ".html/extra",
                page + ".html.", page + "/");
    }

    /**
     * Every page the built package carries, read from the package rather than from a list here.
     *
     * @return the addresses, in the order the package holds them
     */
    private static List<String> pages() {
        final List<String> pages = new ArrayList<>();
        try (var found = Files.walk(REPOSITORY.resolve(CONSOLE))) {
            found.filter(path -> ".content.xml".equals(String.valueOf(path.getFileName())))
                    .map(path -> REPOSITORY.relativize(path.getParent()).toString()
                            .replace('\\', '/'))
                    .map(path -> path.substring(path.indexOf("/jcr_root") + "/jcr_root".length()))
                    .sorted()
                    .forEach(pages::add);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
        return pages;
    }

    /**
     * Every value the corpus plants, read from the corpus rather than restated here.
     *
     * @return the planted values
     */
    private static List<String> plantedValues() {
        return read(REPOSITORY.resolve("policy/redaction-corpus.toml")).lines()
                .filter(line -> line.startsWith("planted = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .toList();
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
