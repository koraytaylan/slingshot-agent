// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What the platform does to a state-changing request before this product sees it.
 *
 * <p>The referrer filter is the prerequisite nobody expects, because it has the shape of a
 * command-line client: a program that sends no {@code Referer} at all. This asks a running instance
 * for the answer rather than repeating what the documentation says, on the two requests that
 * matter — one with a referrer naming the instance, one with none — and writes down which of them
 * reaches a servlet.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ForgeryProtectionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** A path under the platform's own write servlet, which is state-changing by any measure. */
    private static final String A_WRITE = "/var/forgery-";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /**
     * The bundle a deployment's referrer filter lives in.
     *
     * <p>Absent from this tier, which is the finding this scenario records: the public image is a
     * Sling starter without the security bundle, so what refuses a foreign referrer on a customer's
     * author cannot be exhibited here. What can be exhibited is that nothing else refuses it, which
     * is what makes the deployment guide's naming of the filter necessary rather than decorative.
     * </p>
     */
    private static final String REFERRER_FILTER_BUNDLE = "org.apache.sling.security";

    /** What the platform answers when it has created something. */
    private static final int CREATED = 201;

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
    @DisplayName("this tier carries no referrer filter, which is why the guide names it")
    void thistierCarriesNoReferrerFilter() {
        assertEquals(java.util.Optional.empty(), tier.bundleState(REFERRER_FILTER_BUNDLE),
                "this tier now carries the security bundle, so the refusals this scenario records"
                        + " as absent have to be proved here instead of written down");
        final String path = A_WRITE + System.nanoTime();
        assertEquals(CREATED, requests.submitWithReferrer(tier.address() + path,
                        List.of("jcr:primaryType", "nt:unstructured"),
                        "https://somewhere-else.example/").statusCode(),
                "a tier with no referrer filter refused a foreign referrer, so something else is"
                        + " refusing state-changing requests here");
    }

    @Test
    @DisplayName("a write naming the instance as its referrer is served")
    void awriteNamingTheInstanceIsServed() {
        final String path = A_WRITE + System.nanoTime();
        assertEquals(CREATED, requests.submitWithReferrer(tier.address() + path,
                        List.of("jcr:primaryType", "nt:unstructured"), tier.address() + "/")
                .statusCode(),
                "a state-changing request naming the instance was refused, so a client that does"
                        + " what the deployment guide says cannot write at all");
    }

    @Test
    @DisplayName("the deployment guide names the filter, what it refuses, and the relaxation to avoid")
    void thedeploymentGuideNamesTheFilter() {
        final String deployment = read(REPOSITORY.resolve("docs/DEPLOYMENT.md"));
        assertTrue(deployment.contains("org.apache.sling.security.impl.ReferrerFilter"),
                "the filter a customer's own instance carries is not named for an operator");
        assertTrue(deployment.contains("Referer"),
                "what a client has to send is not written down");
        assertTrue(deployment.contains("allow.empty"),
                "the one relaxation an operator must not make is not written down");
        assertTrue(deployment.contains("allow.hosts"),
                "the way an operator may allow a client's own host is not written down");
        final String path = A_WRITE + System.nanoTime();
        assertEquals(CREATED, requests.submit(tier.address() + path,
                        List.of("jcr:primaryType", "nt:unstructured")).statusCode(),
                "a request with no referrer at all was refused on a tier that carries no filter,"
                        + " so this scenario is measuring something other than the filter");
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
