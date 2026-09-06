// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Boundary evidence for bounded reference discovery. */
@ExtendWith(SlingContextExtension.class)
final class RepositoryReachTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void multivalueReferencesAreFound() {
        sling.create().resource("/content/target");
        sling.create().resource("/content/source", Map.of("links",
                new String[]{"/content/other", "/content/target"}));

        final RepositoryReach.References references = RepositoryReach.references(
                sling.resourceResolver(), "/content/target", 10);

        assertTrue(references.complete());
        assertTrue(references.found().stream().anyMatch(resource ->
                "/content/source".equals(resource.getPath())));
    }

    @Test
    void exhaustedVisibilityIsNeverReportedComplete() {
        sling.create().resource("/content/target");
        sling.create().resource("/content/source", Map.of("link", "/content/target"));

        final RepositoryReach.References references = RepositoryReach.references(
                sling.resourceResolver(), "/content/target", 1);

        assertFalse(references.complete());
        assertTrue(references.found().isEmpty());
    }
}
