// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.Resource;
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
    void multivalueReferencesAreRepointedEntryByEntry() {
        sling.create().resource("/content/source", Map.of("links",
                new String[]{"/content/target", "/content/other", "/content/target"}));

        final Resource source = sling.resourceResolver().getResource("/content/source");
        assertEquals(2, RepositoryReach.repointed(List.of(source), "/content/target",
                "/content/moved"));

        assertEquals(List.of("/content/moved", "/content/other", "/content/moved"),
                List.of(source.getValueMap().get("links", String[].class)));
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

    @Test
    void aWideTreeStopsAtTheBoundWithoutPresentingACompletePrefix() {
        sling.create().resource("/content/wide");
        for (int child = 0; child < 1000; child++) {
            sling.create().resource("/content/wide/child-" + child);
        }

        final List<String> reached = RepositoryReach.under(
                sling.resourceResolver().getResource("/content/wide"), 1);

        assertEquals(2, reached.size(), "a bound-one walk retained more than one node past its bound");
        assertEquals(List.of("/content/wide", "/content/wide/child-0"), reached);
    }

    @Test
    void aDeepTreeKeepsTheActivePathWithinTheBound() {
        String path = "/content/deep";
        sling.create().resource(path);
        final StringBuilder deepest = new StringBuilder(path);
        for (int depth = 0; depth < 100; depth++) {
            deepest.append("/child");
            sling.create().resource(deepest.toString());
        }

        final List<String> reached = RepositoryReach.under(
                sling.resourceResolver().getResource("/content/deep"), 100);

        assertEquals(101, reached.size(), "the deep walk did not stop one node past its bound");
        assertEquals(deepest.toString(), reached.get(reached.size() - 1));
    }
}
