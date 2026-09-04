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
    @DisplayName("every supported value type round-trips with what it is travelling beside it")
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
        assertEquals(new DocumentValue.Whole(42),
                valueOf(properties, "count"), "a whole number was carried as something else");
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                valueOf(properties, "enabled"), "a truth was carried as the word for one");
        assertEquals(new DocumentValue.Text("a string"), valueOf(properties, "text"));
        assertEquals(new DocumentValue.Text("1.25"), valueOf(properties, "exact"),
                "a decimal lost precision on the way through");
    }

    @Test
    @DisplayName("every type this build claims to support maps, and every one it does not is named")
    void thesupportedSetIsExactlyWhatItClaims() {
        for (final RepositoryValueKind kind : RepositoryValueKind.values()) {
            assertEquals(kind, RepositoryValueKind.of(kind.code()).orElseThrow(),
                    kind + " does not map back to itself from the repository's own code");
            assertTrue(!kind.spelling().isBlank(), kind + " is spelled as nothing");
            assertInstanceOf(DocumentValue.class, RepositoryValueKind.documentValueOf(kind, "1"),
                    kind + " renders no value at all");
        }
        assertEquals(ELEVEN, RepositoryValueKind.values().length,
                "a supported type was added or lost without this suite being told");
        assertEquals(java.util.Optional.empty(), RepositoryValueKind.of(PropertyType.BINARY),
                "a binary is claimed as supported, and nothing can write one back");
        assertEquals(java.util.Optional.empty(), RepositoryValueKind.of(PropertyType.UNDEFINED),
                "an undefined type is claimed as supported");
        assertEquals(PropertyType.nameFromValue(PropertyType.BINARY),
                RepositoryValueKind.unsupportedName(PropertyType.BINARY),
                "an unsupported type is not named the way the repository names it");
        assertTrue(RepositoryValueKind.unsupportedName(UNKNOWN_CODE).contains("-"),
                "a code the repository itself does not know is not reported at all");
    }

    /** How many repository value types this build represents faithfully. */
    private static final int ELEVEN = 11;

    /** A type code no repository declares, which even the repository cannot name. */
    private static final int UNKNOWN_CODE = 99;

    @Test
    @DisplayName("a long property whose written form is not a number is carried as what was there")
    void anonNumericLongIsCarriedHonestly() {
        assertEquals(new DocumentValue.Text("not-a-number"),
                RepositoryValueKind.documentValueOf(RepositoryValueKind.LONG, "not-a-number"),
                "a repository disagreeing with itself was answered with a number nobody stored");
    }

    @Test
    @DisplayName("a value type this build cannot round-trip is refused naming the type and where")
    void anunsupportedTypeIsRefusedByName() throws RepositoryException {
        final Node node = nodeAt("/content/binary");
        node.setProperty("payload", session().getValueFactory()
                .createBinary(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3})));
        session().save();
        final LoadContentResult.Refused refused = assertInstanceOf(LoadContentResult.Refused.class,
                LoadContentResult.of(node, 0, NODE_BOUND),
                "a binary was rendered rather than refused, so what came back cannot be written"
                        + " back");
        assertEquals(LoadContentResult.UNSUPPORTED_VALUE, refused.category());
        assertTrue(refused.detail().contains("payload"),
                "the refusal does not name the property: " + refused.detail());
        assertTrue(refused.detail().contains(PropertyType.nameFromValue(PropertyType.BINARY)),
                "the refusal does not name the type: " + refused.detail());
        assertTrue(refused.detail().contains("/content/binary"),
                "the refusal does not say where in the caller's own content it is");
    }

    @Test
    @DisplayName("a depth of zero is the addressed node alone, and each level adds one generation")
    void depthIsHonouredExactly() throws RepositoryException {
        final Node root = nodeAt("/content/deep");
        final Node child = root.addNode("child", "nt:unstructured");
        child.addNode("grandchild", "nt:unstructured").addNode("great", "nt:unstructured");
        session().save();
        assertTrue(children(rendered(root, 0)).members().isEmpty()
                        || !rendered(root, 0).members().containsKey(LoadContentResult.CHILDREN),
                "a depth of zero included children");
        assertEquals(List.of("child"),
                List.copyOf(children(rendered(root, 1)).members().keySet()));
        assertEquals(List.of(), List.copyOf(children(childOf(rendered(root, 1), "child"))
                        .members().keySet()),
                "a depth of one reached a grandchild");
        assertEquals(List.of("grandchild"),
                List.copyOf(children(childOf(rendered(root, 2), "child")).members().keySet()));
        assertTrue(!childOf(rendered(root, 2), "child").members().containsKey("great"),
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
        return assertInstanceOf(LoadContentResult.Rendered.class,
                LoadContentResult.of(node, depth, NODE_BOUND), "the subtree was refused").document();
    }

    private static DocumentValue.Mapping propertiesOf(DocumentValue.Mapping node) {
        return assertInstanceOf(DocumentValue.Mapping.class,
                node.member(LoadContentResult.PROPERTIES).orElseThrow());
    }

    private static DocumentValue.Mapping children(DocumentValue.Mapping node) {
        return node.member(LoadContentResult.CHILDREN)
                .map(value -> assertInstanceOf(DocumentValue.Mapping.class, value))
                .orElseGet(() -> new DocumentValue.Mapping(new LinkedHashMap<>()));
    }

    private static DocumentValue.Mapping childOf(DocumentValue.Mapping node, String name) {
        return assertInstanceOf(DocumentValue.Mapping.class,
                children(node).member(name).orElseThrow(
                        () -> new AssertionError(name + " is not among the children")));
    }

    private static String typeOf(DocumentValue.Mapping properties, String name) {
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(name).orElseThrow(
                        () -> new AssertionError(name + " is not among the properties")));
        return assertInstanceOf(DocumentValue.Text.class,
                property.member(LoadContentResult.TYPE).orElseThrow()).value();
    }

    private static DocumentValue valueOf(DocumentValue.Mapping properties, String name) {
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(name).orElseThrow());
        return property.member(LoadContentResult.VALUE).orElseThrow();
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
