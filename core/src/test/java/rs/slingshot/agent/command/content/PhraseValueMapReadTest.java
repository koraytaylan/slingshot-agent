// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import rs.slingshot.agent.json.DocumentValue;

/** Phrase matching adapts each content node once without retaining values between visits. */
@ExtendWith(SlingContextExtension.class)
final class PhraseValueMapReadTest {

    private static final String ROOT = "/content/synthetic-phrase-adaptation";
    private static final String PHRASE = "synthetic needle";
    private static final int PAGES = 128;
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @ParameterizedTest
    @CsvSource({ "title,true,2,2,0", "description,true,2,2,1",
                 "absent,false,1,1,1", "unsearched,false,1,1,1" })
    void phrasePropertiesShareOneCurrentAdaptation(String location, boolean matched,
            long adaptationsPerPage, long titleReadsPerPage, long descriptionReadsPerPage) {
        final var traversal = PhraseSearchTraversal.of(ROOT, PHRASE);
        final var reads = new Reads();
        IntStream.range(0, PAGES).forEach(index -> {
            final String path = ROOT + "/page-" + index;
            final Resource page = createPage(path, "title".equals(location) ? PHRASE : "synthetic title",
                    "description".equals(location) ? PHRASE : "synthetic description");
            if ("unsearched".equals(location)) {
                values(page).put("synthetic:unsearched", PHRASE);
            }
            final var row = traversal.row().apply(reads.page(page));
            assertEquals(matched, row.isPresent());
            row.ifPresent(value -> {
                assertEquals(Set.of("repository_path", "title"), value.members().keySet());
                assertEquals(new DocumentValue.Text(path), value.member("repository_path").orElseThrow());
                assertEquals(new DocumentValue.Text("title".equals(location) ? PHRASE : "synthetic title"),
                        value.member("title").orElseThrow());
            });
        });
        assertEquals(PAGES * titleReadsPerPage, reads.title.get());
        assertEquals(PAGES * descriptionReadsPerPage, reads.description.get());
        assertEquals(0, reads.unsearched.get(), "the content map must never be enumerated");
        assertEquals(PAGES * adaptationsPerPage, reads.adaptations.get(),
                "matching the two searched properties must share one current value map");
    }

    @Test
    void subsequentVisitsReadChangedValuesAndCurrentTitles() {
        final Resource page = createPage(ROOT + "/changing", PHRASE, "synthetic description");
        final var traversal = PhraseSearchTraversal.of(ROOT, PHRASE);
        final var reads = new Reads();
        final Resource current = reads.page(page);
        assertTrue(traversal.row().apply(current).isPresent());
        values(page).put("jcr:title", "synthetic replacement");
        values(page).put("jcr:description", PHRASE);
        final var changed = traversal.row().apply(current).orElseThrow();
        assertEquals(new DocumentValue.Text("synthetic replacement"), changed.member("title").orElseThrow());
        values(page).put("jcr:description", "synthetic absent");
        assertTrue(traversal.row().apply(current).isEmpty());
        assertEquals(5, reads.adaptations.get());
        assertEquals(0, reads.unsearched.get());
    }

    private Resource createPage(String path, String title, String description) {
        final Resource page = sling.create().resource(path, "jcr:primaryType", "cq:Page");
        sling.create().resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent",
                "jcr:title", title, "jcr:description", description,
                "synthetic:unsearched", "synthetic private excerpt");
        return page;
    }

    private static ModifiableValueMap values(Resource page) {
        return Objects.requireNonNull(Objects.requireNonNull(page.getChild("jcr:content"))
                .adaptTo(ModifiableValueMap.class));
    }

    private static final class Reads {
        private final AtomicLong adaptations = new AtomicLong();
        private final AtomicLong title = new AtomicLong();
        private final AtomicLong description = new AtomicLong();
        private final AtomicLong unsearched = new AtomicLong();

        private Resource page(Resource delegate) {
            return new ResourceWrapper(delegate) {
                @Override
                public Resource getChild(String path) {
                    return new ResourceWrapper(Objects.requireNonNull(super.getChild(path))) {
                        @Override
                        public ValueMap getValueMap() {
                            adaptations.incrementAndGet();
                            return observed(super.getValueMap());
                        }
                    };
                }
            };
        }

        private ValueMap observed(ValueMap delegate) {
            return new ValueMapDecorator(delegate) {
                @Override
                public <Value> Value get(String name, Value fallback) {
                    switch (name) {
                        case "jcr:title" -> title.incrementAndGet();
                        case "jcr:description" -> description.incrementAndGet();
                        default -> unsearched.incrementAndGet();
                    }
                    return super.get(name, fallback);
                }
            };
        }
    }
}
