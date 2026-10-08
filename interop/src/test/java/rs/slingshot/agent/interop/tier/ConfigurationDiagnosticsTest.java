// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Startup diagnostics identify public causes without copying log messages or private paths. */
final class ConfigurationDiagnosticsTest {

    private static final String EMPTY = "runtime_exception_types=[], repository_failure_codes=[], "
            + "unresolved_conflict=false";

    @Test
    void absentCausesAreExplicit() {
        assertEquals(EMPTY, PublicSlingTier.configurationDiagnostics(""));
    }

    @Test
    void actualExceptionTokensAndRepositoryCodesAreSelectedOnce() {
        final String output = PublicSlingTier.configurationDiagnostics(
                "javax.jcr.InvalidItemStateException: OakState0001 Unresolved conflicts\n"
                        + "Caused by: javax.jcr.InvalidItemStateException: synthetic details\n"
                        + "Caused by: java.lang.IllegalStateException: synthetic details");
        assertEquals("runtime_exception_types=[javax.jcr.InvalidItemStateException, "
                + "java.lang.IllegalStateException], repository_failure_codes=[OakState0001], "
                + "unresolved_conflict=true", output);
    }

    @Test
    void errorHandlerNamesAndEmbeddedFragmentsAreNotExceptionTokens() {
        assertEquals(EMPTY, PublicSlingTier.configurationDiagnostics(
                "org.apache.sling.api.servlets.JakartaErrorHandler "
                        + "org.apache.sling.servlets.resolver.internal.defaults.DefaultErrorHandler "
                        + "prefixjavax.jcr.RepositoryExceptionSuffix prefixOakState0001Suffix"));
    }

    @Test
    void messagesPathsAndUnrelatedTypesAreNotCopied() {
        final String output = PublicSlingTier.configurationDiagnostics(
                "javax.jcr.RepositoryException: /content/synthetic-private-reference "
                        + "synthetic-secret-marker\n"
                        + "synthetic.private.TenantFailureException: synthetic-secret-marker");
        assertTrue(output.contains("javax.jcr.RepositoryException"));
        assertFalse(output.contains("/content/"));
        assertFalse(output.contains("synthetic-secret-marker"));
        assertFalse(output.contains("TenantFailureException"));
    }

    @Test
    void distinctTypesAndCodesAreBounded() {
        final String input = IntStream.range(0, 16).mapToObj(index ->
                "javax.jcr.Synthetic" + index + "Exception: OakState" + String.format("%04d", index))
                .reduce("", (all, row) -> all + row + "\n");
        final String output = PublicSlingTier.configurationDiagnostics(input);
        assertTrue(output.contains("Synthetic0Exception"));
        assertTrue(output.contains("Synthetic7Exception"));
        assertFalse(output.contains("Synthetic8Exception"));
        assertFalse(output.contains("Synthetic15Exception"));
        assertTrue(output.contains("OakState0007"));
        assertFalse(output.contains("OakState0008"));
    }
}
