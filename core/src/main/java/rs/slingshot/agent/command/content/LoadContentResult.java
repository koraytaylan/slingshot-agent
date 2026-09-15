// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import rs.slingshot.agent.command.ArtifactDescriptor;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One repository subtree rendered exactly, or refused by name.
 *
 * <p>This is the command everything else is compared against, so what it does with a value it does
 * not understand matters more than what it does with the rest. It refuses. A loader that quietly
 * renders an unknown type as its string form produces output nobody can trust to round-trip: the
 * caller cannot tell that string from a genuine one, and writing it back writes something else
 * into somebody's repository.</p>
 *
 * <h2>Depth is exact</h2>
 *
 * <p>A depth of zero is the addressed node and its properties alone — not an error, and not its
 * children. Each level adds one generation. A subtree one level deeper than asked for is not
 * included, because "about this much" is not something a caller can act on and a loader that
 * rounded up would be reading content its caller did not ask to see.</p>
 */
public final class LoadContentResult {

    private LoadContentResult() {
    }

    /** The member a node's own properties are carried in. */
    public static final String PROPERTIES = "properties";

    /** The member a node's children are carried in as a sequence. */
    public static final String CHILDREN = "children";

    /** The member saying whether readable children exist beyond the requested depth. */
    public static final String CHILDREN_TRUNCATED = "children_truncated";

    /** The member one property's value is carried in. */
    public static final String VALUE = "value";

    /** The member one property's values are carried in, where it holds more than one. */
    public static final String VALUES = "values";

    /** The member saying what a property's type is, so a caller writing it back knows. */
    public static final String PROPERTY_TYPE = "property_type";

    /** The member saying the property's cardinality. */
    public static final String CARDINALITY = "cardinality";

    /** The wire spelling for a single-valued property. */
    public static final String SINGLE = "single";

    /** The wire spelling for a multiple-valued property. */
    public static final String MULTIPLE = "multiple";

    /** The member the addressed path is echoed in, so an answer says what it is about. */
    public static final String PATH = "path";

    /** The member saying which of the two shapes this answer has. */
    public static final String DISPOSITION = "disposition";

    /** The slot this command declares for a document too large to answer with. */
    public static final String LOADED_CONTENT_SLOT =
            rs.slingshot.agent.store.ArtifactSlot.LOADED_CONTENT_SLOT;

    /** What kind of file that slot always holds, which the client compares exactly. */
    public static final String LOADED_CONTENT_MEDIA_TYPE =
            rs.slingshot.agent.store.ArtifactSlot.LOADED_CONTENT_MEDIA_TYPE;

    /** The name that slot always suggests, which the client compares exactly. */
    public static final String LOADED_CONTENT_FILE_NAME = "loaded-content.json";

    /** How an answer carrying its own document is spelled. */
    public static final String INLINE = "inline";

    /** How an answer carrying a reference to one is spelled. */
    public static final String ARTIFACT = "artifact";

    /** The member the loaded subtree itself is carried in, where the answer carries it. */
    public static final String DOCUMENT = "document";

    /**
     * Every member a load's result has, in either of its two shapes.
     *
     * <p>The document itself is one member and its inside is not declared. That is the client's own
     * schema and it is right: a repository subtree's shape is the repository's, and a schema that
     * tried to describe it would be a second, weaker copy of the type mapping this build already
     * holds.</p>
     */
    public static final java.util.List<String> MEMBERS = java.util.List.of(ARTIFACT,
            ArtifactDescriptor.BYTE_LENGTH, ArtifactDescriptor.DIGEST, DISPOSITION, DOCUMENT,
            ArtifactDescriptor.IDENTIFIER, ArtifactDescriptor.MEDIA_TYPE, PATH,
            ArtifactDescriptor.SLOT, ArtifactDescriptor.SUGGESTED_FILE_NAME);

    /** What rendering produced: the document, or the one reason there is none. */
    public sealed interface Outcome permits Rendered, Refused {
    }

    /**
     * A subtree this build represents faithfully.
     *
     * @param document the rendered subtree
     * @param nodesRead how many nodes were examined, which is what the load budget counts
     */
    public record Rendered(DocumentValue document, long nodesRead) implements Outcome {

        /**
         * Whether this document is too large to carry in the answer.
         *
         * <p>The bound is the command's own and the client's: a charged document that exceeds it
         * must be answered by reference, because a document that arrives inline under the
         * transport's larger bound would be refused against the command's smaller one. The count
         * charged is the document's alone — not the path, the discriminator, the descriptor, or
         * the envelope, none of which the client counts.</p>
         *
         * @param inlineBound the largest document this command may carry inline
         * @return whether it must be published instead
         */
        public boolean overflowed(long inlineBound) {
            return documentBytes().length > inlineBound;
        }

        /**
         * This document as the canonical bytes a reader would be served.
         *
         * @return the bytes, in the one form this build writes
         */
        public byte[] documentBytes() {
            final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(document);
            if (!(written instanceof final CanonicalByteWriter.Written bytes)) {
                // A document this build cannot write has no byte count, and an uncountable answer
                // cannot be decided about. The walk produces only values the writer takes, so this
                // is a defect in this file rather than anything a caller did.
                throw new IllegalStateException("the rendered subtree has no canonical form");
            }
            return bytes.bytes();
        }
    }

    /**
     * One it does not.
     *
     * @param category the declared failure category
     * @param detail what was refused, naming the property and the type rather than the value
     */
    public record Refused(String category, String detail) implements Outcome {
    }

    /** The category an unsupported repository value is refused under. */
    public static final String UNSUPPORTED_VALUE = "unsupported_repository_value";

    /** The category a walk that ran out of budget is refused under. */
    public static final String BUDGET_EXCEEDED = "load_budget_exceeded";

    /**
     * The whole answer one load produces, assembled by the type the answer belongs to.
     *
     * @param rendered what the walk produced
     * @param repositoryPath the path that was asked for
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(Rendered rendered, String repositoryPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(PATH, new DocumentValue.Text(repositoryPath));
        result.put(DISPOSITION, new DocumentValue.Text(INLINE));
        result.put(DOCUMENT, rendered.document());
        return new DocumentValue.Mapping(result);
    }

    /**
     * The answer one load produces where its document was too large to carry.
     *
     * <p>The two shapes are told apart by a declared disposition rather than by which member is
     * present, so a reader knows which answer it has before it looks for anything. The digest is
     * the artifact's own, which a reader verifies for itself rather than trusting the transfer.</p>
     *
     * @param repositoryPath the path that was asked for
     * @param published where the document went and what it is
     * @param identifier the artifact's own identifier, which is the document's own digest
     * @param mediaType what kind of file it is
     * @param fileName the name a reader should save it under
     * @return the result document
     */
    public static DocumentValue.Mapping artifactOf(String repositoryPath,
                                                   OverflowPublication.Published published,
                                                   String identifier, String mediaType,
                                                   String fileName) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(PATH, new DocumentValue.Text(repositoryPath));
        result.put(DISPOSITION, new DocumentValue.Text(ARTIFACT));
        result.put(ARTIFACT, ArtifactDescriptor.documentOf(published, identifier, mediaType,
                fileName));
        return new DocumentValue.Mapping(result);
    }

    /**
     * Renders one subtree to exactly the depth asked for.
     *
     * @param node the addressed node
     * @param depth how many generations below it to include, where zero is the node alone
     * @param nodeBudget how many nodes may be examined before the walk is abandoned
     * @return the rendered subtree, or the one reason there is none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome of(Node node, long depth, long nodeBudget) throws RepositoryException {
        final Walk walk = new Walk(nodeBudget);
        final Outcome rendered = walk.node(node, 0, depth);
        return rendered instanceof final Rendered held
                ? new Rendered(held.document(), walk.read.get()) : rendered;
    }

    /**
     * The order the client's own reader requires children in.
     *
     * <p>Ascending by the child's own name, where a same-name sibling's name carries its {@code
     * [n]} index — so {@code name} sorts before {@code name[2]}, and the order is the client's own
     * rather than the order the repository happened to hand the nodes over in. The client compares
     * this order and refuses a document whose children are not strictly ascending, so a subtree
     * rendered in the wrong order is a subtree it cannot read.</p>
     */
    private enum LocalNameOrder implements java.util.Comparator<Node> {
        /** Ascending by local name, index last. */
        ASCENDING;

        @Override
        public int compare(Node first, Node second) {
            return keyOf(first).compareTo(keyOf(second));
        }

        /**
         * One node's ordering key: its local name, then its same-name index.
         *
         * @param node the node
         * @return the key
         */
        private static String keyOf(Node node) {
            try {
                final int index = node.getIndex();
                return index <= 1
                        ? node.getName()
                        : node.getName() + "[" + index + "]";
            } catch (final RepositoryException unreadable) {
                // A node whose own name cannot be read has no place in an ordered answer, and the
                // walk that reaches one is already failing on the same repository.
                throw new IllegalStateException(unreadable);
            }
        }
    }

    /**
     * One walk of one subtree, which carries the count of what it has examined.
     *
     * <p>The count is here rather than passed down and back because every level has to see the same
     * one: a budget counted per branch is a budget a wide tree never reaches and a deep one reaches
     * immediately, which is a bound that means something different for every shape of content.</p>
     */
    private static final class Walk {

        private final long nodeBudget;
        private final java.util.concurrent.atomic.AtomicLong read =
                new java.util.concurrent.atomic.AtomicLong();

        Walk(long nodeBudget) {
            this.nodeBudget = nodeBudget;
        }

        Outcome node(Node node, long currentDepth, long maxDepth) throws RepositoryException {
            if (read.incrementAndGet() > nodeBudget) {
                return new Refused(BUDGET_EXCEEDED, "this load examined more than the "
                        + nodeBudget + " nodes it is allowed, and stopped rather than going on");
            }
            final SequencedMap<String, DocumentValue> rendered = new LinkedHashMap<>();
            final Outcome properties = properties(node, rendered);
            if (properties instanceof Refused) {
                return properties;
            }
            rendered.put(PATH, new DocumentValue.Text(node.getPath()));
            if (currentDepth == maxDepth) {
                rendered.put(CHILDREN, new DocumentValue.Sequence(List.of()));
                rendered.put(CHILDREN_TRUNCATED, new DocumentValue.Flag(node.hasNodes() ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
                return new Rendered(new DocumentValue.Mapping(rendered), read.get());
            }
            return children(node, currentDepth, maxDepth, rendered);
        }

        private Outcome properties(Node node, SequencedMap<String, DocumentValue> into)
                throws RepositoryException {
            final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
            final PropertyIterator held = node.getProperties();
            while (held.hasNext()) {
                final Property property = held.nextProperty();
                final Outcome one = property(node, property, properties);
                if (one instanceof Refused) {
                    return one;
                }
            }
            into.put(PROPERTIES, new DocumentValue.Mapping(properties));
            return new Rendered(new DocumentValue.Mapping(into), read.get());
        }

        private Outcome property(Node node, Property property,
                                 SequencedMap<String, DocumentValue> into)
                throws RepositoryException {
            final Optional<RepositoryValueKind> kind =
                    RepositoryValueKind.of(property.getType());
            if (kind.isEmpty()) {
                return new Refused(UNSUPPORTED_VALUE, property.getName() + " at " + node.getPath()
                        + " is a " + RepositoryValueKind.unsupportedName(property.getType())
                        + ", which this build does not represent faithfully; it is refused rather"
                        + " than rendered as text nobody could write back");
            }
            final SequencedMap<String, DocumentValue> one = new LinkedHashMap<>();
            one.put(CARDINALITY, new DocumentValue.Text(property.isMultiple() ? MULTIPLE : SINGLE));
            one.put(PROPERTY_TYPE, new DocumentValue.Text(kind.get().spelling()));
            if (property.isMultiple()) {
                final List<DocumentValue> values = new ArrayList<>();
                for (final Value value : property.getValues()) {
                    long len = 0;
                    if (kind.get() == RepositoryValueKind.BINARY) {
                        try {
                            len = countBytes(value.getBinary());
                        } catch (java.io.IOException e) {
                            throw new RepositoryException(e);
                        }
                    }
                    values.add(RepositoryValueKind.documentValueOf(kind.get(), value, len));
                }
                one.put(VALUES, new DocumentValue.Sequence(List.copyOf(values)));
            } else {
                one.put(VALUE,
                        RepositoryValueKind.documentValueOf(kind.get(), property.getValue(), property.getLength()));
            }
            into.put(property.getName(), new DocumentValue.Mapping(one));
            return new Rendered(new DocumentValue.Mapping(into), read.get());
        }

        private static long countBytes(javax.jcr.Binary binary) throws java.io.IOException, RepositoryException {
            try (java.io.InputStream is = binary.getStream()) {
                long count = 0;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    count += read;
                }
                return count;
            }
        }

        private Outcome children(Node node, long currentDepth, long maxDepth,
                                     SequencedMap<String, DocumentValue> into)
                throws RepositoryException {
            final List<Node> childNodes = new ArrayList<>();
            final NodeIterator held = node.getNodes();
            while (held.hasNext()) {
                childNodes.add(held.nextNode());
            }
            childNodes.sort(LocalNameOrder.ASCENDING);
            final List<DocumentValue> renderedChildren = new ArrayList<>();
            for (final Node child : childNodes) {
                try {
                    final Outcome one = node(child, currentDepth + 1, maxDepth);
                    if (one instanceof Refused) {
                        return one;
                    }
                    renderedChildren.add(((Rendered) one).document());
                } catch (RepositoryException e) {
                    throw e;
                }
            }
            into.put(CHILDREN, new DocumentValue.Sequence(renderedChildren));
            // The walk reached this node's children, so nothing was left out and the answer says
            // so. The flag is the client's own: at a depth below the maximum it must be false,
            // because a walk that included the children has truncated nothing.
            into.put(CHILDREN_TRUNCATED, new DocumentValue.Flag(DocumentValue.Truth.FALSE));
            return new Rendered(new DocumentValue.Mapping(into), read.get());
        }
    }
}
