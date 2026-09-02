// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a type may be called and what shape it may have, with the pattern register beside it.
 *
 * <p>The register is the part that cannot rot. A significant type with no row cannot appear without
 * somebody deciding what it is, and a row naming a type that has gone fails the build rather than
 * sitting there describing nothing.</p>
 */
final class ApiShapePolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/api-shape");

    @Test
    @DisplayName("this repository holds every naming and shape rule")
    void thisRepositoryHoldsTheShapeRules() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("the refused suffix is refused on a top-level and on a nested type alike")
    void theRefusedSuffixIsRefusedEverywhere() {
        assertRule(findings("impl-suffix.java"), "impl-suffix", "ReaderImpl");
        assertRule(findings("nested-impl-suffix.java"), "impl-suffix", "InnerImpl");
    }

    @Test
    @DisplayName("both implementation-naming rules are refused, and the two findings are distinct")
    void bothImplementationNamingRulesAreRefused() {
        assertRule(namingFindings("sole-implementation.java"), "sole-implementation-naming",
                "FileReader is the only implementation of Reader and is not named DefaultReader");
        assertRule(namingFindings("several-implementations.java"), "several-implementations-naming",
                "DefaultTier is one of 2 implementations of Tier and claims to be the default");
        assertEquals(List.of(), namingFindings("record-and-enum.java"),
                "an enum implementing an interface was not counted as an implementation of it");
    }

    @Test
    @DisplayName("a public field, a non-final field, and an extensible public type are refused")
    void theThreeShapeRulesAreRefusedDistinctly() {
        assertRule(findings("public-field.java"), "public-field", "text");
        assertRule(findings("non-final-field.java"), "non-final-field", "text");
        assertRule(findings("extensible-public-type.java"), "extension-point", "ExtensiblePublicType");
    }

    @Test
    @DisplayName("a named constant is data rather than state, and the accepted file passes")
    void aNamedConstantIsNotState() {
        assertEquals(List.of(), findings("accepted.java"));
    }

    @Test
    @DisplayName("every exemption the shape policy records carries a reason")
    void everyExemptionCarriesAReason() {
        assertTrue(!policy().exemptions().isEmpty(), "the policy records no exemption at all");
        policy().exemptions().forEach(exemption -> assertTrue(!exemption.reason().isBlank(),
                exemption.kind() + " records no reason"));
    }

    @Test
    @DisplayName("every significant type declares a pattern, and every row names a type that exists")
    void theRegisterAndTheSourceAgree() {
        assertEquals("", register().against(REPOSITORY).render());
    }

    @Test
    @DisplayName("a register row naming a type that does not exist is refused")
    void aRowNamingNoTypeIsRefused() {
        assertRule(registerAt("register-names-a-missing-type.toml").against(REPOSITORY).findings(),
                "pattern-register", "NothingLikeThis");
    }

    @Test
    @DisplayName("a declared builder whose built type has a setter is refused naming both")
    void aBuilderWhoseBuiltTypeCanChangeIsRefused() {
        final PolicyReport report =
                registerAt("mismatched-builder.toml").against(FIXTURES);
        assertRule(report.findings(), "pattern-signature", "MutableBuilder builds HalfBuilt");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.symbol().contains("setText")),
                "the finding does not name the setter: " + report.render());
    }

    @Test
    @DisplayName("a guard that is extensible, holds a second thing, or has a second way in is refused")
    void aguardWithAWayAroundItIsRefused() {
        final PolicyReport report = registerAt("leaky-proxy.toml").against(FIXTURES);
        assertRule(report.findings(), "pattern-signature",
                "LeakyProxy declares protection-proxy and is not final");
        assertRule(report.findings(), "pattern-signature",
                "LeakyProxy declares protection-proxy and holds 2 things");
        assertRule(report.findings(), "pattern-signature",
                "LeakyProxy declares protection-proxy and offers 2 ways in");
    }

    @Test
    @DisplayName("every declared pattern carries a signature and a reason")
    void everyPatternCarriesASignatureAndAReason() {
        assertTrue(!register().patterns().isEmpty(), "the register holds no pattern at all");
        register().patterns().forEach(pattern -> {
            assertTrue(!pattern.signature().isBlank(), pattern.name() + " carries no signature");
            assertTrue(!pattern.reason().isBlank(), pattern.name() + " carries no reason");
        });
        register().types().forEach(row -> assertTrue(!row.reason().isBlank(),
                row.name() + " declares a pattern and no reason for it"));
    }

    @Test
    @DisplayName("a type declaring a pattern the vocabulary does not hold refuses the register")
    void anUnknownPatternRefusesTheRegister() {
        final Path fixture = FIXTURES.resolve("unknown-pattern.toml");
        RepositoryTree.text(REPOSITORY.resolve("policy/design-patterns.toml"));
        assertTrue(DesignPatternRegister.readRegister(fixture)
                        instanceof DesignPatternRegister.Refused,
                "a register naming a pattern with no signature was accepted");
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static List<PolicyFinding> namingFindings(String fixture) {
        return policy().implementationNamingFindings(FIXTURES, List.of(FIXTURES.resolve(fixture)));
    }

    private static ApiShapePolicy policy() {
        return assertInstanceOf(ApiShapePolicy.Loaded.class, ApiShapePolicy.read(REPOSITORY),
                "the shape policy was refused").policy();
    }

    private static DesignPatternRegister register() {
        return assertInstanceOf(DesignPatternRegister.Loaded.class,
                DesignPatternRegister.read(REPOSITORY), "the register was refused").register();
    }

    private static DesignPatternRegister registerAt(String fixture) {
        return assertInstanceOf(DesignPatternRegister.Loaded.class,
                DesignPatternRegister.readRegister(FIXTURES.resolve(fixture)),
                fixture + " was refused").register();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }
}
