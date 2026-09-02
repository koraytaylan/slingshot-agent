// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The licence this product is offered under, on every file that carries one.
 *
 * <p>Two fixtures decide whether the check is real: a Java file naming the expression inside a
 * string literal, and a document naming it inside a value. Both mention every word of the header
 * and neither carries one, so a check reading the first bytes would accept them and this one
 * refuses.</p>
 */
final class LicenceHeadersTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/licence-headers");

    @Test
    @DisplayName("every repository-owned file carries the expression and the copyright line")
    void everyOwnedFileCarriesTheHeader() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("the expression is the sibling's own, and the licence texts are unmodified")
    void theLicenceIsTheSiblingsOwn() {
        final LicenceHeaders policy = policy();
        assertEquals("MIT OR Apache-2.0", policy.expression());
        assertEquals("Copyright 2026 Koray Taylan Davgana", policy.copyright());
        final String combined = read(REPOSITORY.resolve("LICENSE"));
        assertTrue(combined.contains("MIT OR Apache-2.0"), "the combined text states no expression");
        assertTrue(combined.contains(policy.copyright()), "the combined text states no copyright");
        assertTrue(combined.contains(read(REPOSITORY.resolve("LICENSE-MIT")).strip()),
                "the combined text does not reproduce the MIT text unmodified");
        assertTrue(combined.contains(read(REPOSITORY.resolve("LICENSE-APACHE")).strip()),
                "the combined text does not reproduce the Apache text unmodified");
    }

    @Test
    @DisplayName("the aggregator states the licence and no module states its own")
    void theLicenceIsDeclaredOnce() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        assertEquals(List.of("MIT OR Apache-2.0"),
                reactor.aggregator().getLicenses().stream()
                        .map(licence -> licence.getName()).toList());
        reactor.modules().forEach(module ->
                assertTrue(reactor.raw(module).getLicenses().isEmpty(),
                        module + " declares its own licence where the aggregator owns it"));
    }

    @Test
    @DisplayName("a file with no header, the wrong expression, or the wrong copyright is refused")
    void theThreeHeaderFailuresAreDistinct() {
        assertRule(findings("no-header.java"), "licence-header", "carries no");
        assertRule(findings("wrong-expression.java"), "licence-header", "Apache-2.0");
        assertRule(findings("wrong-copyright.java"), "licence-copyright",
                "Copyright 2026 Koray Taylan Davgana");
        assertEquals(List.of(), findings("accepted.java"));
    }

    @Test
    @DisplayName("a header inside a string literal or a value is not a header")
    void aHeaderInsideAValueIsNotAHeader() {
        assertRule(findings("header-in-a-string-literal.java"), "licence-header", "carries no");
        assertRule(findings("header-in-text.xml"), "licence-header", "carries no");
        assertRule(findings("header-in-a-value.toml"), "licence-header", "carries no");
        assertEquals(List.of(), findings("accepted.xml"));
        assertEquals(List.of(), findings("accepted.toml"));
    }

    @Test
    @DisplayName("an excluded path is excluded only where the policy names it, with a reason")
    void exclusionsAreNamedAndReasoned() {
        final LicenceHeaders policy = policy();
        assertTrue(!policy.applies(Path.of("mvnw.cmd")), "the wrapper script is not excluded");
        assertTrue(!policy.applies(Path.of("LICENSE-MIT")), "the licence text is not excluded");
        assertTrue(policy.applies(Path.of("core/pom.xml")), "a manifest this repository owns is excluded");
        assertTrue(policy.applies(Path.of("policy/module-direction.toml")),
                "a policy this repository owns is excluded");
    }

    @Test
    @DisplayName("the notice's claim is checked rather than trusted")
    void theNoticeClaimHoldsAgainstTheBuiltArtifacts() {
        final String version = ReactorModel.at(REPOSITORY).aggregator().getVersion();
        List.of("core", "aem").forEach(module -> {
            final BuiltArtifact bundle = BuiltArtifact.at(REPOSITORY.resolve(module).resolve("target")
                    .resolve("slingshot-agent-" + module + "-" + version + ".jar"));
            final List<String> foreign = bundle.entryNames().stream()
                    .filter(entry -> !entry.startsWith("rs/slingshot/agent/"))
                    .filter(entry -> !entry.startsWith("META-INF/maven/rs.slingshot/"))
                    .filter(entry -> !"META-INF/MANIFEST.MF".equals(entry))
                    .filter(entry -> !entry.startsWith("OSGI-INF/rs.slingshot.agent."))
                    // The metatype descriptor the build generates from this bundle's own
                    // annotation, which is how an operator sees a configuration in their console
                    // with the reason it is there. Generated from this repository's source, named
                    // after this repository's own type, and shipped by nobody else.
                    .filter(entry -> !entry.startsWith("OSGI-INF/metatype/rs.slingshot.agent."))
                    .filter(entry -> !"OSGI-INF/metatype/".equals(entry))
                    .toList();
            assertEquals(List.of(), foreign, module
                    + " ships a class or resource from another party, and the notice says otherwise");
        });
        assertTrue(read(REPOSITORY.resolve("NOTICE")).contains("embed nothing"),
                "the notice does not state what the check proves");
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static LicenceHeaders policy() {
        return assertInstanceOf(LicenceHeaders.Loaded.class, LicenceHeaders.read(REPOSITORY),
                "the licence policy was refused").policy();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule()) && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
