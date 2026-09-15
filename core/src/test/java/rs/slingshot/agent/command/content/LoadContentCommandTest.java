// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The command everything else is compared against, held to what it claims.
 *
 * <p>The assertion that matters most is the refusal: a value type this build does not represent
 * faithfully is refused by name rather than rendered as text. Everything else here can be got right
 * by a loader whose output nobody can write back.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class LoadContentCommandTest {

    /** How deep this suite lets a walk go, which is deeper than anything it builds. */
    private static final long DEPTH_BOUND = 32;

    /** How many nodes this suite lets a walk examine, which is more than anything it builds. */
    private static final long NODE_BOUND = 1000;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every supported value type round-trips in the spelling the client's reader takes")
    void everySupportedTypeRoundTrips() throws RepositoryException {
        final Node node = nodeAt("/content/every-type");
        node.setProperty("text", "a string");
        node.setProperty("count", 42L);
        node.setProperty("enabled", true);
        node.setProperty("ratio", 1.5d);
        node.setProperty("exact", new BigDecimal("1.25"));
        node.setProperty("when", Calendar.getInstance());
        session().save();
        final DocumentValue.Mapping properties = propertiesOf(rendered(node, 0));
        assertEquals("string", typeOf(properties, "text"));
        assertEquals("long", typeOf(properties, "count"));
        assertEquals("boolean", typeOf(properties, "enabled"));
        assertEquals("double", typeOf(properties, "ratio"));
        assertEquals("decimal", typeOf(properties, "exact"));
        assertEquals("date", typeOf(properties, "when"));
        // A whole number travels as its minimal decimal spelling, not as a JSON number: the
        // client's own reader compares the digits rather than parsing a number that could have
        // been rounded, so a JSON number is a value it refuses.
        assertEquals(new DocumentValue.Text("42"),
                valueOf(properties, "count"), "a whole number was carried as something else");
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                valueOf(properties, "enabled"), "a truth was carried as the word for one");
        assertEquals(new DocumentValue.Text("a string"), valueOf(properties, "text"));
        assertEquals(new DocumentValue.Text("1.25"), valueOf(properties, "exact"),
                "a decimal lost precision on the way through");
        // A double travels as the sixteen lowercase hexadecimal digits of its binary64 bits,
        // which is the one spelling that can carry a non-finite value and round-trip exactly.
        assertEquals(new DocumentValue.Text(
                        String.format("%016x", Double.doubleToRawLongBits(1.5d))),
                valueOf(properties, "ratio"),
                "a floating-point number was not carried as its binary64 bits");
    }

    @Test
    @DisplayName("a property carries the cardinality and type the client's reader validates")
    void aPropertyCarriesItsCardinalityAndType() throws RepositoryException {
        // The client's reader is a closed three-member document: cardinality, property_type, and
        // one of value or values. A property written any other way is one it cannot read, which
        // is the difference between a result it settles and a result it never acquires.
        final Node node = nodeAt("/content/cardinality");
        node.setProperty("single", "one");
        node.setProperty("many", new String[] {"a", "b"});
        session().save();
        final DocumentValue.Mapping properties = propertiesOf(rendered(node, 0));
        assertEquals(new DocumentValue.Text("single"), cardinalityOf(properties, "single"),
                "a single-valued property did not say it holds one value");
        assertEquals(new DocumentValue.Text("multiple"), cardinalityOf(properties, "many"),
                "a multiple-valued property did not say it holds a sequence");
        assertInstanceOf(DocumentValue.Mapping.class, properties.member("single").orElseThrow(),
                "a property was not carried as the document the reader expects");
        assertEquals(java.util.Set.of("cardinality", "property_type", "value"),
                ((DocumentValue.Mapping) properties.member("single").orElseThrow()).members()
                        .keySet(),
                "a single-valued property carries members the reader does not know");
        assertEquals(java.util.Set.of("cardinality", "property_type", "values"),
                ((DocumentValue.Mapping) properties.member("many").orElseThrow()).members()
                        .keySet(),
                "a multiple-valued property carries members the reader does not know");
    }

    @Test
    @DisplayName("every type this build claims to support maps, and every one it does not is named")
    void thesupportedSetIsExactlyWhatItClaims() throws RepositoryException {
        for (final RepositoryValueKind kind : RepositoryValueKind.values()) {
            assertEquals(kind, RepositoryValueKind.of(kind.code()).orElseThrow(),
                    kind + " does not map back to itself from the repository's own code");
            assertTrue(!kind.spelling().isBlank(), kind + " is spelled as nothing");
            assertInstanceOf(DocumentValue.class,
                    RepositoryValueKind.documentValueOf(
                            kind, session().getValueFactory().createValue("1"), 0),
                    kind + " renders no value at all");
        }
        assertEquals(TWELVE, RepositoryValueKind.values().length,
                "a supported type was added or lost without this suite being told");
        assertEquals(java.util.Optional.empty(), RepositoryValueKind.of(PropertyType.UNDEFINED),
                "an undefined type is claimed as supported");
        assertEquals(PropertyType.nameFromValue(PropertyType.UNDEFINED),
                RepositoryValueKind.unsupportedName(PropertyType.UNDEFINED),
                "an unsupported type is not named the way the repository names it");
        assertTrue(RepositoryValueKind.unsupportedName(UNKNOWN_CODE).contains("-"),
                "a code the repository itself does not know is not reported at all");
    }

    /** How many repository value types this build represents faithfully. */
    private static final int TWELVE = 12;

    /** A type code no repository declares, which even the repository cannot name. */
    private static final int UNKNOWN_CODE = 99;

    @Test
    @DisplayName("a binary carries its length and never its bytes, which is what the client reads")
    void aBinaryCarriesItsLengthRatherThanItsBytes() throws RepositoryException {
        final Node node = nodeAt("/content/binary");
        node.setProperty("payload", session().getValueFactory()
                .createBinary(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3})));
        session().save();
        final DocumentValue.Mapping properties = propertiesOf(rendered(node, 0));
        assertEquals("binary", typeOf(properties, "payload"),
                "a binary was not carried as a binary");
        // The client's own reader asks for one member naming the length as a string, so a value
        // that answered with the bytes would be both unreadable and a read of content the caller
        // asked only the size of.
        assertEquals(java.util.Map.of("byte_length", "3"),
                renderedBytesMetadata(properties, "payload"));
    }

    @Test
    @DisplayName("a type this build does not represent is refused by name, not rendered as text")
    void anunsupportedTypeIsRefusedByName() {
        // Every type a repository can store is one this build represents, so the refusal cannot be
        // reached through a real node today. What it guards is the day the repository's own closed
        // set gains a thirteenth code: the walk asks this mapping for a name and gets none, which
        // is what makes it refuse rather than render something nobody could write back. The
        // refusal's own text is asserted here against the mapping rather than through a node,
        // because a node cannot carry a type outside the set.
        assertEquals(java.util.Optional.empty(), RepositoryValueKind.of(UNKNOWN_CODE),
                "a code no repository declares was claimed as supported");
        final String named = RepositoryValueKind.unsupportedName(UNKNOWN_CODE);
        assertTrue(named.contains("-"),
                "a code the repository itself cannot name was not reported distinctly: " + named);
        assertEquals(PropertyType.nameFromValue(PropertyType.UNDEFINED),
                RepositoryValueKind.unsupportedName(PropertyType.UNDEFINED),
                "an undefined type is not named the way the repository names it");
    }

    @Test
    @DisplayName("a depth of zero is the addressed node alone, and each level adds one generation")
    void depthIsHonouredExactly() throws RepositoryException {
        final Node root = nodeAt("/content/deep");
        final Node child = root.addNode("child", "nt:unstructured");
        child.addNode("grandchild", "nt:unstructured").addNode("great", "nt:unstructured");
        session().save();
        assertTrue(children(rendered(root, 0)).items().isEmpty(),
                "a depth of zero included children");
        final List<String> children1 = children(rendered(root, 1)).items().stream()
                .map(answer -> assertInstanceOf(DocumentValue.Mapping.class, answer)
                        .member(LoadContentResult.PATH).orElseThrow())
                .map(named -> assertInstanceOf(DocumentValue.Text.class, named).value())
                .toList();
        assertTrue(children1.contains("/content/deep/child"),
                "a depth of one did not include the child");
        assertTrue(children(childOf(rendered(root, 1), "child")).items().isEmpty(),
                "a depth of one reached a grandchild");
        final List<String> children2 = children(childOf(rendered(root, 2), "child")).items().stream()
                .map(answer -> assertInstanceOf(DocumentValue.Mapping.class, answer)
                        .member(LoadContentResult.PATH).orElseThrow())
                .map(named -> assertInstanceOf(DocumentValue.Text.class, named).value())
                .toList();
        assertTrue(children2.contains("/content/deep/child/grandchild"),
                "a depth of two did not include the grandchild");
        assertTrue(!childOf(rendered(root, 2), "child").member(LoadContentResult.CHILDREN)
                        .map(held -> ((DocumentValue.Sequence) held).items().stream()
                                .anyMatch(grandchild -> {
                                    final DocumentValue.Mapping mapping =
                                            assertInstanceOf(DocumentValue.Mapping.class, grandchild);
                                    return mapping.member(LoadContentResult.PATH)
                                            .map(path -> assertInstanceOf(DocumentValue.Text.class,
                                                    path).value())
                                            .orElse("")
                                            .endsWith("/great");
                                }))
                        .orElse(false),
                "a depth of two reached a great-grandchild");
    }

    @Test
    @DisplayName("a walk that runs past its node budget stops and says so rather than truncating")
    void awalkPastItsBudgetStops() throws RepositoryException {
        final Node root = nodeAt("/content/wide");
        for (int child = 0; child < WIDE; child = child + 1) {
            root.addNode("child-" + child, "nt:unstructured");
        }
        session().save();
        final LoadContentResult.Refused refused = assertInstanceOf(LoadContentResult.Refused.class,
                LoadContentResult.of(root, 1, WIDE / 2),
                "a walk past its budget answered with a subtree rather than refusing");
        assertEquals(LoadContentResult.BUDGET_EXCEEDED, refused.category());
    }

    /** How many children one deliberately wide subtree has. */
    private static final int WIDE = 20;

    @Test
    @DisplayName("the count of nodes examined is what the walk actually examined")
    void thecountIsWhatWasExamined() throws RepositoryException {
        final Node root = nodeAt("/content/counted");
        root.addNode("one", "nt:unstructured");
        root.addNode("two", "nt:unstructured");
        session().save();
        final LoadContentResult.Rendered held = assertInstanceOf(LoadContentResult.Rendered.class,
                LoadContentResult.of(root, 1, NODE_BOUND));
        assertEquals(3, held.nodesRead(),
                "the node count is not the node count, so a caller cannot compare it to a budget");
    }

    @Test
    @DisplayName("the address is required; an omitted depth is the addressed node by itself")
    void theaddressIsRequiredAndTheDepthIsNot() {
        assertEquals(LoadContentCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument(null, 1L)).refusal(),
                "an argument with no address was given one");
        // The client's own schema makes the depth optional. An omitted one reaches the addressed
        // node and nothing else: any deeper default would walk content the caller did not ask for,
        // which is the one direction a default must not err in.
        assertEquals(LoadContentCommand.THE_NODE_ALONE,
                assertInstanceOf(LoadContentCommand.Held.class,
                        LoadContentCommand.of(argument("/content", null), DEPTH_BOUND),
                        "a caller who named an address and no depth was refused")
                        .command().depth());
        assertEquals(LoadContentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content", 1L)).refusal());
        assertEquals(LoadContentCommand.Refusal.DEPTH_ABOVE_MAXIMUM,
                refusalOf(argument("/content", DEPTH_BOUND + 1)).refusal());
        assertInstanceOf(LoadContentCommand.Held.class,
                LoadContentCommand.of(argument("/content", 0L), DEPTH_BOUND),
                "a depth of zero was refused, and zero is the addressed node by itself");
    }

    @Test
    @DisplayName("a member nobody declared is refused rather than ignored")
    void anundeclaredMemberIsRefused() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(LoadContentCommand.PATH, new DocumentValue.Text("/content"));
        members.put(LoadContentCommand.DEPTH, new DocumentValue.Whole(1));
        members.put("follow_references", new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        assertEquals(LoadContentCommand.Refusal.MEMBER_UNKNOWN,
                refusalOf(new DocumentValue.Mapping(members)).refusal(),
                "an argument nobody declared was ignored, and a caller who wrote it would believe"
                        + " it had been honoured");
    }

    @Test
    @DisplayName("the declared categories are exactly the row's, in both directions")
    void thecategoriesAreTheRowsOwn() {
        assertEquals(List.of("access_denied", "load_budget_exceeded", "not_found",
                        "unsupported_repository_value"),
                new LoadContentHandler().categories().stream().sorted().toList(),
                "the handler can produce a category its row does not declare, or the other way");
    }

    private static LoadContentCommand.Refused refusalOf(DocumentValue arguments) {
        return assertInstanceOf(LoadContentCommand.Refused.class,
                LoadContentCommand.of(arguments, DEPTH_BOUND), "the argument was accepted");
    }

    private static DocumentValue argument(String path, Long depth) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (path != null) {
            members.put(LoadContentCommand.PATH, new DocumentValue.Text(path));
        }
        if (depth != null) {
            members.put(LoadContentCommand.DEPTH, new DocumentValue.Whole(depth));
        }
        return new DocumentValue.Mapping(members);
    }

    private DocumentValue.Mapping rendered(Node node, long depth) throws RepositoryException {
        final LoadContentResult.Outcome outcome = LoadContentResult.of(node, depth, NODE_BOUND);
        final LoadContentResult.Rendered rendered =
                assertInstanceOf(LoadContentResult.Rendered.class, outcome,
                        "the subtree was refused");
        return assertInstanceOf(DocumentValue.Mapping.class, rendered.document());
    }

    private static DocumentValue.Mapping propertiesOf(DocumentValue.Mapping node) {
        return assertInstanceOf(DocumentValue.Mapping.class,
                node.member(LoadContentResult.PROPERTIES).orElseThrow());
    }

    private static DocumentValue.Sequence children(DocumentValue.Mapping node) {
        return node.member(LoadContentResult.CHILDREN)
                .map(value -> assertInstanceOf(DocumentValue.Sequence.class, value))
                .orElseGet(() -> new DocumentValue.Sequence(List.of()));
    }

    private static DocumentValue.Mapping childOf(DocumentValue.Mapping node, String name) {
        final DocumentValue.Sequence children = children(node);
        final DocumentValue.Mapping child = children.items().stream()
                .map(entry -> assertInstanceOf(DocumentValue.Mapping.class, entry))
                .filter(held -> held.member(LoadContentResult.PATH)
                        .map(path -> assertInstanceOf(DocumentValue.Text.class, path).value())
                        .orElse("")
                        .endsWith("/" + name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not among the children"));
        return child;
    }

    private static String typeOf(DocumentValue.Mapping properties, String name) {
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(name).orElseThrow(
                        () -> new AssertionError(name + " is not among the properties")));
        return assertInstanceOf(DocumentValue.Text.class,
                property.member(LoadContentResult.PROPERTY_TYPE).orElseThrow()).value();
    }

    private static DocumentValue valueOf(DocumentValue.Mapping properties, String name) {
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(name).orElseThrow());
        return property.member(LoadContentResult.VALUE).orElseThrow();
    }

    private static DocumentValue cardinalityOf(DocumentValue.Mapping properties, String name) {
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(name).orElseThrow());
        return property.member(LoadContentResult.CARDINALITY).orElseThrow();
    }

    /**
     * One property's bytes metadata: its one member's name and the length it carries.
     *
     * <p>Compared as data rather than as classes, because what the client's reader validates is
     * the member's name and the spelling of its value.</p>
     */
    private static java.util.Map<String, String> renderedBytesMetadata(
            DocumentValue.Mapping properties, String name) {
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Mapping.class, properties.member(name).orElseThrow())
                        .member(LoadContentResult.VALUE).orElseThrow());
        return property.members().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                java.util.Map.Entry::getKey,
                entry -> assertInstanceOf(DocumentValue.Text.class, entry.getValue()).value()));
    }

    private Node nodeAt(String path) throws RepositoryException {
        Node node = session().getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session().save();
        return node;
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
    }
}
