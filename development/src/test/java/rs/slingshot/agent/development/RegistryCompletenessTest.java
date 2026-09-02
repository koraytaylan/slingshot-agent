// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether this registry and the client's published table are the same sixty-four rows.
 *
 * <p>Each rejection is proved on a copy of the committed registry with exactly one thing wrong with
 * it, so a failure names the thing rather than the directory. The committed pair is checked whole
 * in the first assertion, which is what makes the others mean something.</p>
 */
final class RegistryCompletenessTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES = REPOSITORY.resolve(
            "development/src/test/resources/fixtures/registry-completeness");

    @Test
    @DisplayName("the registry and the client's table are the same sixty-four rows, field for field")
    void thetwoTablesAgree() {
        assertEquals("", RegistryCompleteness.against(
                RegistryCompleteness.Sources.of(REPOSITORY)).render());
        assertEquals(RegistryCompleteness.SIXTYFOUR_COMMANDS,
                RegistryCompleteness.rowsIn(
                        REPOSITORY.resolve(RegistryCompleteness.REGISTRY_DIRECTORY)).size(),
                "the registry is not sixty-four rows");
        assertEquals(RegistryCompleteness.SIXTYFOUR_COMMANDS,
                RegistryCompleteness.publishedRows(REPOSITORY).size(),
                "the client's published table is not sixty-four rows, which would mean this check"
                        + " is comparing against something other than what it thinks");
    }

    @Test
    @DisplayName("the rows come back in ascending wire-name order however the directory enumerates")
    void therowsAreAscending() {
        assertTrue(RegistryCompleteness.isAscending(
                        REPOSITORY.resolve(RegistryCompleteness.REGISTRY_DIRECTORY)),
                "the rows come back in whatever order the file system hands them over, and a"
                        + " listing's order is the file system's business rather than an answer");
        assertTrue(RegistryCompleteness.isAscending(FIXTURES.resolve("missing-row/commands")),
                "a directory missing a row still has to come back sorted, because the order is a"
                        + " property of this check rather than of any file");
    }

    @Test
    @DisplayName("a row either side lacks is a finding naming which side lacks it")
    void thetwoSidesAreNamedSeparately() {
        assertRule(against("missing-row"), RegistryCompleteness.UNIMPLEMENTED,
                "declared by no row here");
        assertRule(against("extra-row"), RegistryCompleteness.UNPUBLISHED,
                "the client publishes nothing by that name");
        assertRule(against("extra-row"), RegistryCompleteness.WRONG_COUNT,
                "both halves of this protocol have 64");
    }

    @Test
    @DisplayName("access, key requirement and result bound are three findings, not one")
    void thethreeFieldsAreReportedSeparately() {
        assertRule(against("access-disagrees"), RegistryCompleteness.ACCESS_DISAGREES,
                "is write here and read to the client");
        assertRule(against("key-disagrees"), RegistryCompleteness.KEY_DISAGREES,
                "so a resend would have a second effect on one side");
        assertRule(against("bound-disagrees"), RegistryCompleteness.BOUND_DISAGREES,
                "so one half would refuse an answer the other sent");
    }

    @Test
    @DisplayName("two rows a caller could confuse for one another are refused, naming both")
    void acollidingIdentityIsRefused() {
        final String rendered = against("identity-collides");
        assertTrue(rendered.contains(RegistryCompleteness.IDENTITY_COLLIDES)
                        && rendered.contains("create_page") && rendered.contains("delete_page"),
                "the finding does not name both commands whose identity collides: " + rendered);
    }

    @Test
    @DisplayName("every one of the sixty-four derives a distinct contract identity")
    void allsixtyfourIdentitiesAreDistinct() {
        final List<String> commands = List.copyOf(RegistryCompleteness.rowsIn(
                REPOSITORY.resolve(RegistryCompleteness.REGISTRY_DIRECTORY)).keySet());
        assertEquals(RegistryCompleteness.SIXTYFOUR_COMMANDS, commands.size());
        assertTrue(RegistryCompleteness.publishedVersion(REPOSITORY, "create_page").isPresent(),
                "the client publishes no contract version for a command this registry declares,"
                        + " so the identity this side derives has a field the other half does not");
    }

    private static String against(String fixture) {
        return RegistryCompleteness.against(RegistryCompleteness.Sources.of(REPOSITORY)
                .withRegistry(FIXTURES.resolve(fixture).resolve("commands"))).render();
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
