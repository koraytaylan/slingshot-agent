// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Removal refusals on a real JCR provider, including an earlier ordinary staged removal. */
@ExtendWith(SlingContextExtension.class)
final class PropertyRemovalTest {

    private static final String PATH = "/content/synthetic-protected-removal";

    private static final String ORDINARY = "aaa_synthetic_remove";

    private static final String PROTECTED = "jcr:primaryType";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a protected JCR property is refused rather than escaping the handler")
    void aProtectedJcrPropertyIsADeclaredRemovalRefusal() throws RepositoryException {
        final Resource target = target();
        assertTrue(session().getNode(PATH).getProperty(PROTECTED).getDefinition().isProtected());
        assertEquals(Optional.of(PROTECTED), removing(PROTECTED).immovableIn(values(target)));
        assertFalse(session().hasPendingChanges());
        assertEquals("nt:unstructured", target.getValueMap().get(PROTECTED));
    }

    @Test
    @DisplayName("an ordinary removal before a protected one is staged but never committed")
    void anEarlierRemovalDoesNotSurviveARefusedRequest() throws RepositoryException {
        final Resource target = target();
        assertEquals(Optional.of(PROTECTED), removing(ORDINARY, PROTECTED)
                .immovableIn(values(target)));
        sling.resourceResolver().revert();
        assertFalse(session().hasPendingChanges());
        assertEquals("synthetic-original", target.getValueMap().get(ORDINARY));
        assertEquals("nt:unstructured", target.getValueMap().get(PROTECTED));
    }

    @Test
    @DisplayName("a provider that explicitly disallows removal refuses without changing the map")
    void anUnmodifiableMapIsADeclaredRemovalRefusal() {
        final Map<String, Object> held = Map.of(ORDINARY, "synthetic-original");
        assertEquals(Optional.of(ORDINARY), removing(ORDINARY).immovableIn(held));
        assertEquals("synthetic-original", held.get(ORDINARY));
    }

    @Test
    @DisplayName("ordinary and already absent properties retain the existing successful behavior")
    void ordinaryAndAbsentPropertiesCanBeRemoved() {
        final Map<String, Object> held = new LinkedHashMap<>(Map.of(ORDINARY, "synthetic-original"));
        assertEquals(Optional.empty(), removing(ORDINARY, "synthetic_absent").immovableIn(held));
        assertTrue(held.isEmpty());
    }

    @Test
    @DisplayName("an unexpected provider failure remains uncertain rather than claiming no effect")
    void anUnexpectedProviderFailureIsNotConvertedIntoADeclaredRefusal() {
        final Map<String, Object> held = new InterruptedRemoval();
        assertThrows(IllegalStateException.class, () -> removing(ORDINARY).immovableIn(held));
    }

    private Resource target() throws RepositoryException {
        final Resource target = sling.create().resource(PATH, ORDINARY, "synthetic-original");
        session().save();
        return target;
    }

    private Session session() {
        return java.util.Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private static ModifiableValueMap values(Resource target) {
        return java.util.Objects.requireNonNull(target.adaptTo(ModifiableValueMap.class));
    }

    private static PropertyChange removing(String... names) {
        return new PropertyChange(new LinkedHashMap<>(), new LinkedHashSet<>(List.of(names)));
    }

    private static final class InterruptedRemoval extends LinkedHashMap<String, Object> {

        private static final long serialVersionUID = 1L;

        @Override
        public Object remove(Object key) {
            throw new IllegalStateException("synthetic-provider-interruption");
        }
    }
}
