// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * What a template gives a page made from it: the content a new page starts with.
 *
 * <p>An editable template keeps that content in {@code initial/jcr:content}, and the resource type
 * its pages render with in {@code structure/jcr:content}; a static template keeps both in its own
 * {@code jcr:content}. A page made without them carries its template's name and renders as
 * nothing, which is why the content is copied rather than referred to.</p>
 *
 * <p>Copied through the caller's own session, inside the same commit as the page, so a page that
 * exists is a page that has its content. The repository's own bookkeeping - identifiers, creation
 * stamps and mixins - is left for the repository to write for the new nodes rather than copied
 * from the template's.</p>
 */
public final class TemplateContent {

    /** Where an editable template keeps a new page's content. */
    private static final String INITIAL = "initial/jcr:content";

    /** Where an editable template keeps the resource type its pages render with. */
    private static final String STRUCTURE = "structure/jcr:content";

    /** Where a static template keeps a new page's content. */
    private static final String STATIC = "jcr:content";

    /** The property a resource renders by. */
    public static final String RESOURCE_TYPE = "sling:resourceType";

    /** What the repository writes for itself on every node, and is never copied. */
    private static final Set<String> BOOKKEEPING = Set.of("jcr:primaryType", "jcr:mixinTypes",
            "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:baseVersion", "jcr:predecessors",
            "jcr:versionHistory", "jcr:isCheckedOut");

    private TemplateContent() {
    }

    /**
     * The content a page made from one template starts with, where the template keeps any.
     *
     * @param template the template
     * @return its initial content resource
     */
    public static Optional<Resource> initialOf(Resource template) {
        return Optional.ofNullable(template.getChild(INITIAL))
                .or(() -> Optional.ofNullable(template.getChild(STATIC)));
    }

    /**
     * The resource type pages made from one template render with, where the template names one.
     *
     * @param template the template
     * @return the resource type
     */
    public static Optional<String> resourceTypeOf(Resource template) {
        return Optional.ofNullable(template.getChild(STRUCTURE))
                .or(() -> initialOf(template))
                .map(content -> content.getValueMap().get(RESOURCE_TYPE, String.class));
    }

    /**
     * One resource's own properties, without the repository's bookkeeping.
     *
     * @param source the resource
     * @return the properties a copy of it is written with
     */
    public static Map<String, Object> propertiesOf(Resource source) {
        final Map<String, Object> copied = new LinkedHashMap<>();
        source.getValueMap().forEach((name, value) -> {
            if (!BOOKKEEPING.contains(name)) {
                copied.put(name, value);
            }
        });
        return copied;
    }

    /**
     * Copies every child of one resource beneath another, as deep as it goes, up to a bound.
     *
     * @param session the caller's own session, which commits the copy with the page
     * @param source the resource whose children are copied
     * @param target the resource they are copied beneath
     * @param budget how many nodes the copy may write
     * @return how many nodes it wrote
     * @throws PersistenceException where the repository refuses one
     */
    public static long copyChildren(ResourceResolver session, Resource source, Resource target,
                             long budget) throws PersistenceException {
        long written = 0;
        for (final Resource child : source.getChildren()) {
            if (written >= budget) {
                throw new PersistenceException("the template's initial content holds more than the "
                        + budget + " nodes one page may be made with");
            }
            final Map<String, Object> properties = propertiesOf(child);
            properties.put("jcr:primaryType", child.getValueMap().get("jcr:primaryType",
                    "nt:unstructured"));
            final Resource copy = session.create(target, child.getName(), properties);
            written = written + 1 + copyChildren(session, child, copy, budget - written - 1);
        }
        return written;
    }
}
