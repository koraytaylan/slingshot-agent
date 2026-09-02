// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import rs.slingshot.agent.json.DocumentValue;

/**
 * Where inside a page a component goes.
 *
 * <p>Two shapes, because a page's own content resource and something nested inside it are named
 * differently: the first has no path to give, and the second is a path relative to it. A single
 * string with an empty value standing for the root would make "the page itself" and "somebody
 * forgot to say" the same message.</p>
 */
public sealed interface ComponentParent
        permits ComponentParent.ContentRoot, ComponentParent.Nested {

    /** The member a caller states this in. */
    String ARGUMENT_MEMBER = "content_parent";

    /** How the page's own content resource is spelled. */
    String CONTENT_ROOT = "content_root";

    /** The page's own content resource. */
    record ContentRoot() implements ComponentParent {
    }

    /**
     * Something inside it, named relative to it.
     *
     * @param relativePath where it is, relative to the page's content resource
     */
    record Nested(String relativePath) implements ComponentParent {
    }

    /**
     * Where under a page's content resource this parent is.
     *
     * @param parent which parent it is
     * @param contentPath the page's own content resource
     * @return the parent's own address
     */
    static String pathOf(ComponentParent parent, String contentPath) {
        return switch (parent) {
            case ContentRoot ignored -> contentPath;
            case Nested nested -> contentPath + "/" + nested.relativePath();
        };
    }

    /**
     * Reads the parent one caller named.
     *
     * <p>A relative path is what the client's own schema declares, and one beginning at the root is
     * refused: a component's parent is somewhere inside the page that was named, and a path that
     * could point anywhere would make the page argument decorative.</p>
     *
     * @param written the parent as the caller wrote it
     * @return the parent, or nothing where it is not one this contract takes
     */
    static java.util.Optional<ComponentParent> of(DocumentValue written) {
        if (!(written instanceof final DocumentValue.Text named) || named.value().isEmpty()) {
            return java.util.Optional.empty();
        }
        if (CONTENT_ROOT.equals(named.value())) {
            return java.util.Optional.of(new ContentRoot());
        }
        return named.value().charAt(0) == '/' ? java.util.Optional.empty()
                : java.util.Optional.of(new Nested(named.value()));
    }
}
