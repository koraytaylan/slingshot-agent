// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Declared model field names on a real JCR provider, independently of storage node names. */
@ExtendWith(SlingContextExtension.class)
final class FragmentModelNamesTest {

    private static final String MODEL = "/conf/synthetic/settings/dam/cfm/models/synthetic-model";

    private static final String STORAGE = "synthetic-storage-node";

    private static final String FIELD = "synthetic_field";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a model field is the declared property name rather than its storage node name")
    void declaredNameIsAcceptedAsTheElement() throws RepositoryException {
        declare(STORAGE, Map.of("name", FIELD));
        final FragmentModel model = read();
        assertEquals(List.of(FIELD), model.elementNames());
        assertTrue(model.unknownIn(elements(FIELD)).isEmpty());
    }

    @Test
    @DisplayName("a storage node identifier does not authorize an undeclared fragment element")
    void storageNameIsNotAnAdditionalElement() throws RepositoryException {
        declare(STORAGE, Map.of("name", FIELD));
        assertEquals(Optional.of(STORAGE), read().unknownIn(elements(STORAGE)));
    }

    @Test
    @DisplayName("a relative property declaration names the same stored element")
    void relativePropertyPrefixIsRemoved() throws RepositoryException {
        declare(STORAGE, Map.of("name", "./" + FIELD));
        assertEquals(List.of(FIELD), read().elementNames());
    }

    @Test
    @DisplayName("legacy name-only model declarations retain their original interpretation")
    void absentMetadataRetainsTheLegacyNodeName() throws RepositoryException {
        declare(FIELD, Map.of());
        assertEquals(List.of(FIELD), read().elementNames());
        assertTrue(read().unknownIn(elements(FIELD)).isEmpty());
    }

    @Test
    @DisplayName("an explicit malformed property declaration never falls back to its node name")
    void invalidExplicitDeclarationsRefuseTheModel()
            throws RepositoryException, PersistenceException {
        for (final String name : List.of("", " ", ".", "..", "./", "../synthetic", "/synthetic")) {
            declare(STORAGE, Map.of("name", name));
            assertInstanceOf(FragmentModel.Invalid.class,
                    FragmentModel.at(sling.resourceResolver(), MODEL));
            sling.resourceResolver().delete(java.util.Objects.requireNonNull(
                    sling.resourceResolver().getResource(MODEL + "/"
                            + FragmentHandlers.MODEL_ELEMENTS + "/" + STORAGE)));
            sling.resourceResolver().commit();
        }
    }

    @Test
    @DisplayName("nontext declaration metadata is refused before any element can be written")
    void nontextNameDoesNotFallBackToTheStorageIdentifier() throws RepositoryException {
        declare(STORAGE, Map.of("name", Boolean.TRUE));
        assertInstanceOf(FragmentModel.Invalid.class,
                FragmentModel.at(sling.resourceResolver(), MODEL));
    }

    @Test
    @DisplayName("two model items cannot silently declare the same fragment element")
    void duplicateDeclaredNamesRefuseTheAmbiguousModel() throws RepositoryException {
        declare(STORAGE, Map.of("name", FIELD));
        declare("synthetic-other-storage-node", Map.of("name", "./" + FIELD));
        assertInstanceOf(FragmentModel.Invalid.class,
                FragmentModel.at(sling.resourceResolver(), MODEL));
    }

    private void declare(String storageName, Map<String, Object> metadata)
            throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class));
        if (!List.of(session.getWorkspace().getNamespaceRegistry().getPrefixes()).contains("cq")) {
            session.getWorkspace().getNamespaceRegistry().registerNamespace("cq",
                    "http://www.day.com/jcr/cq/1.0");
        }
        final Map<String, Object> properties = new LinkedHashMap<>(metadata);
        properties.put("jcr:primaryType", "nt:unstructured");
        sling.create().resource(MODEL + "/" + FragmentHandlers.MODEL_ELEMENTS + "/" + storageName,
                properties);
        session.save();
    }

    private FragmentModel read() {
        return assertInstanceOf(FragmentModel.Read.class,
                FragmentModel.at(sling.resourceResolver(), MODEL)).model();
    }

    private static FragmentElements elements(String name) {
        return new FragmentElements(new LinkedHashMap<>(Map.of(name, List.of("synthetic-value"))));
    }
}
