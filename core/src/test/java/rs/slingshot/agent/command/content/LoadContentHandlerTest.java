// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.ArtifactDescriptor;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.ResultDelivery;

/**
 * The handler as a command actually runs: through the caller's own resolver, and nothing else.
 *
 * <p>The two answers worth proving here are the ones a caller cannot get any other way. Content
 * they may not see is answered as absent rather than as forbidden, because the two answers together
 * say whether something exists at a path they have no right to look at. And every failure it
 * produces is one its own row declares, because a category the row does not carry is a failure no
 * client knows how to act on.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class LoadContentHandlerTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REGISTRY = repositoryRoot().resolve("policy/commands");

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a subtree the caller can see is answered with what is in it")
    void asubtreeIsAnswered() throws RepositoryException {
        nodeAt("/content/site").setProperty("title", "A page");
        session().save();
        final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/site", 0), "the subtree was refused");
        assertEquals(new DocumentValue.Text("/content/site"),
                produced.result().member(LoadContentResult.PATH).orElseThrow());
        assertEquals(new DocumentValue.Text(LoadContentResult.INLINE),
                produced.result().member(LoadContentResult.DISPOSITION).orElseThrow(),
                "the answer does not say which of its two shapes it has, so a reader has to guess"
                        + " by looking for a member");
        assertTrue(produced.result().member(LoadContentResult.DOCUMENT).isPresent(),
                "the answer carries no document");
    }

    @Test
    @DisplayName("a path with nothing at it is answered as absent rather than as forbidden")
    void anabsentPathIsNotFound() {
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                run("/content/nothing-is-here", 0), "a path with nothing at it was answered");
        assertEquals(LoadContentHandler.NOT_FOUND, failed.category(),
                "a path nobody can see was told apart from a path nothing is at, which together"
                        + " say whether something exists where the caller may not look");
    }

    @Test
    @DisplayName("an argument this command does not take is refused before the repository is read")
    void abadArgumentIsRefusedFirst() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(LoadContentCommand.PATH, new DocumentValue.Text("relative/path"));
        members.put(LoadContentCommand.DEPTH, new DocumentValue.Whole(0));
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new LoadContentHandler().run(new DocumentValue.Mapping(members),
                        readOnly(), context()),
                "a relative path was accepted");
        assertEquals(LoadContentHandler.ARGUMENT_REJECTED, failed.category());
    }

    @Test
    @DisplayName("the handler writes nothing, because what it is handed cannot")
    void thehandlerWritesNothing() throws RepositoryException {
        nodeAt("/content/untouched").setProperty("title", "before");
        session().save();
        assertInstanceOf(CommandHandler.Produced.class, run("/content/untouched", 0));
        assertEquals("before", session().getNode("/content/untouched")
                        .getProperty("title").getString(),
                "the content changed while a read ran over it");
        assertEquals(null, readOnly().adaptTo(Session.class),
                "a read handler was handed something it could reach a session through");
    }

    @Test
    @DisplayName("a node holding every kind a repository stores is answered as content")
    void aNodeHoldingEveryStoredKindIsAnswered() throws RepositoryException {
        // Every type a repository can store is one this build represents, so the refusal branch is
        // not reachable through a real node; what a caller must be able to rely on is that a
        // subtree holding the awkward kinds — bytes, a floating-point number, a multiple-valued
        // property — is answered rather than refused.
        final Node node = nodeAt("/content/every-kind");
        node.setProperty("payload", session().getValueFactory()
                .createBinary(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3})));
        node.setProperty("ratio", 1.5d);
        node.setProperty("many", new String[] {"a", "b"});
        session().save();
        assertInstanceOf(CommandHandler.Produced.class, run("/content/every-kind", 0),
                "a subtree holding values a caller can be told about was refused");
    }

    @Test
    @DisplayName("a walk past its budget reaches the caller as the budget category")
    void abudgetRefusalReachesTheCaller() throws RepositoryException {
        final Node root = nodeAt("/content/very-wide");
        for (int child = 0; child < WIDE; child = child + 1) {
            root.addNode("child-" + child, "nt:unstructured");
        }
        session().save();
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new LoadContentHandler().run(argument("/content/very-wide", 1), readOnly(),
                        narrowContext()),
                "a walk past its budget answered with a subtree");
        assertEquals(LoadContentResult.BUDGET_EXCEEDED, failed.category());
    }

    @Test
    @DisplayName("a subtree larger than the inline bound is answered as bytes to carry, not inline")
    void asubtreePastTheInlineBoundBecomesAnArtifact() throws RepositoryException {
        final Node root = nodeAt("/content/large");
        // Content whose written document crosses the command's own inline bound, which is far
        // smaller than the contract's general one: a document that fitted the larger bound would
        // be refused by the client against the smaller one the command declares.
        root.setProperty("payload", "x".repeat(LOADED_INLINE_BOUND + 1));
        session().save();
        final CommandHandler.Artifact artifact = assertInstanceOf(CommandHandler.Artifact.class,
                run("/content/large", 0),
                "a document past the command's own inline bound was carried inline anyway");
        assertEquals(LoadContentResult.LOADED_CONTENT_SLOT, artifact.slot());
        assertTrue(artifact.bytes().length > LOADED_INLINE_BOUND,
                "the artifact does not hold the document that did not fit");
        assertEquals(new DocumentValue.Text(LoadContentResult.ARTIFACT),
                artifact.result().member(LoadContentResult.DISPOSITION).orElseThrow(),
                "an answer carried by reference does not say which of its two shapes it has");
    }

    /** The bound this command's own document is carried inline under, from the contract. */
    private static final int LOADED_INLINE_BOUND = Math.toIntExact(CONTRACT.value(
            rs.slingshot.agent.contract.ContractLimit.MAXIMUM_AGENT_INLINE_LOADED_DOCUMENT_BYTES));

    /** How many children one deliberately wide subtree has. */
    private static final int WIDE = 12;

    /** A discovery budget smaller than the wide subtree, so the walk cannot finish. */
    private static CallerContext narrowContext() {
        return new CallerContext(operation(), new Budget(Budget.Kind.DISCOVERY, WIDE / 2),
                Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT, CONTRACT.value(
                        rs.slingshot.agent.contract.ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static DocumentValue.Mapping argument(String path, long depth) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(LoadContentCommand.PATH, new DocumentValue.Text(path));
        members.put(LoadContentCommand.DEPTH, new DocumentValue.Whole(depth));
        return new DocumentValue.Mapping(members);
    }

    @Test
    @DisplayName("a repository that refused the caller is told apart from one that simply failed")
    void arefusalIsToldApartFromAFailure() {
        assertEquals(LoadContentHandler.ACCESS_DENIED,
                LoadContentHandler.whenTheRepositoryFails(
                        new javax.jcr.AccessDeniedException("no"), "/content/some").category(),
                "a caller the repository refused was told the content is not there, which is a"
                        + " different thing and not one they can act on");
        final CommandHandler.Failed other = LoadContentHandler.whenTheRepositoryFails(
                new RepositoryException("the store fell over"), "/content/some");
        assertEquals(LoadContentHandler.NOT_FOUND, other.category());
        assertTrue(other.detail().contains("RepositoryException"),
                "the failure does not say what happened: " + other.detail());
        assertTrue(!other.detail().contains("fell over"),
                "the repository's own message reached a caller, and nobody decided it was safe to");
    }

    @Test
    @DisplayName("every category this handler produces is one its own committed row declares")
    void everycategoryIsTheRowsOwn() {
        final RegistryRow row = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REGISTRY), "the committed registry was refused")
                .registry().row(LoadContentCommand.WIRE_NAME).orElseThrow();
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new LoadContentHandler().categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
        assertTrue(!new LoadContentHandler().categories()
                        .contains(LoadContentHandler.ARGUMENT_REJECTED),
                "an argument refusal is the dispatcher's category rather than this command's, and"
                        + " a row declaring it would be claiming a failure it cannot produce");
    }

    @Test
    @DisplayName("a property holding several values is answered as several, of its own type")
    void amultiValuedPropertyIsAnsweredAsSeveral() throws RepositoryException {
        final javax.jcr.Node node = nodeAt("/content/multi");
        node.setProperty("labels", new String[] {"first", "second"});
        // A multi-valued binary is the case whose count is measured rather than
        // reported: the property's own length is not the sum of its values', and
        // a reader told the wrong length cannot ask for the right bytes back.
        node.setProperty("payloads", new javax.jcr.Value[] {
                session().getValueFactory().createValue(
                        session().getValueFactory().createBinary(
                                new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}))),
                session().getValueFactory().createValue(
                        session().getValueFactory().createBinary(
                                new java.io.ByteArrayInputStream(new byte[] {4, 5, 6, 7})))});
        session().save();
        final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/multi", 0), "the subtree was refused");
        final DocumentValue.Mapping document = (DocumentValue.Mapping) produced.result()
                .member(LoadContentResult.DOCUMENT).orElseThrow();
        final DocumentValue.Mapping properties = (DocumentValue.Mapping) document
                .member(LoadContentResult.PROPERTIES).orElseThrow();
        final DocumentValue.Mapping labels = (DocumentValue.Mapping) properties
                .member("labels").orElseThrow();
        assertEquals(new DocumentValue.Text("multiple"),
                labels.member(LoadContentResult.CARDINALITY).orElseThrow());
        assertEquals(2, ((DocumentValue.Sequence) labels
                .member(LoadContentResult.VALUES).orElseThrow()).items().size());
        final DocumentValue.Mapping payloads = (DocumentValue.Mapping) properties
                .member("payloads").orElseThrow();
        final List<DocumentValue> held = ((DocumentValue.Sequence) payloads
                .member(LoadContentResult.VALUES).orElseThrow()).items();
        assertEquals(2, held.size(), "a multi-valued binary was not answered as several");
        assertEquals("3", ((DocumentValue.Mapping) held.get(0))
                .member("byte_length").map(LoadContentHandlerTest::textOf).orElseThrow(),
                "the first binary's length is not the length of its own bytes");
        assertEquals("4", ((DocumentValue.Mapping) held.get(1))
                .member("byte_length").map(LoadContentHandlerTest::textOf).orElseThrow(),
                "the second binary's length is not the length of its own bytes");
    }

    private static String textOf(DocumentValue value) {
        return ((DocumentValue.Text) value).value();
    }

    @Test
    @DisplayName("a same-name sibling is ordered by the index the client writes it with")
    void sameNameSiblingsAreOrderedByTheirIndex() throws RepositoryException {
        // A repository that permits same-name siblings is the only way a node
        // carries an index past the first, and the ordering key is what decides
        // where such a node goes. The client compares this order and refuses a
        // document whose children are not strictly ascending, so the two
        // spellings are what a reader's own tree is rebuilt from.
        assertEquals("alpha", LoadContentResult.LocalNameOrder.keyOf("alpha", 1),
                "the first of a name is the plain name");
        assertEquals("alpha[2]", LoadContentResult.LocalNameOrder.keyOf("alpha", 2),
                "the second of a name carries the index the client reads");
        assertTrue(LoadContentResult.LocalNameOrder.keyOf("alpha", 1)
                        .compareTo(LoadContentResult.LocalNameOrder.keyOf("alpha", 2)) < 0,
                "the first of a name does not sort before the second");
        assertTrue(LoadContentResult.LocalNameOrder.keyOf("alpha[2]", 1)
                        .compareTo(LoadContentResult.LocalNameOrder.keyOf("beta", 1)) < 0,
                "a name with an index does not sort by its name alone");
        // The ordering itself, over real nodes: it is what the walk hands the
        // answer, so an order taken from the repository rather than from the
        // keys would put the children back in an order the client refuses.
        final javax.jcr.Node parent = nodeAt("/content/compare");
        parent.addNode("beta", "nt:unstructured");
        parent.addNode("alpha", "nt:unstructured");
        session().save();
        final List<String> ordered = new ArrayList<>();
        for (final Node child : LoadContentResult.LocalNameOrder.of(parent)) {
            ordered.add(child.getName());
        }
        assertEquals(List.of("alpha", "beta"), ordered,
                "the children came back in the repository's order rather than the reader's");
        final javax.jcr.Node empty = nodeAt("/content/compare/alpha");
        assertEquals(List.of(), namesIn(LoadContentResult.LocalNameOrder.of(empty)),
                "a node with no children was given some");
    }

    private static List<String> namesIn(List<Node> nodes) throws RepositoryException {
        final List<String> names = new ArrayList<>();
        for (final Node node : nodes) {
            names.add(node.getName());
        }
        return names;
    }

    @Test
    @DisplayName("a document too large to carry is answered as a reference to it")
    void anoversizedDocumentBecomesAReference() {
        final DocumentValue.Mapping answered = LoadContentResult.artifactOf("/content/site",
                new OverflowPublication.Published("result",
                        new ResultDelivery.Artifact(4096, digest())),
                "an-artifact-identifier", "application/json", "site.json");
        assertEquals(new DocumentValue.Text(LoadContentResult.ARTIFACT),
                answered.member(LoadContentResult.DISPOSITION).orElseThrow(),
                "the answer does not say which of its two shapes it has, so a reader has to guess"
                        + " by looking for a member");
        assertTrue(answered.member(LoadContentResult.DOCUMENT).isEmpty(),
                "an answer carrying a reference carried a document as well, and a reader told both"
                        + " has to decide which to believe");
        final DocumentValue.Mapping artifact = (DocumentValue.Mapping) answered
                .member(LoadContentResult.ARTIFACT).orElseThrow();
        assertEquals(new DocumentValue.Whole(4096),
                artifact.member(ArtifactDescriptor.BYTE_LENGTH).orElseThrow());
        assertEquals(new DocumentValue.Text("site.json"),
                artifact.member(ArtifactDescriptor.SUGGESTED_FILE_NAME).orElseThrow());
        assertEquals(DigestValue.RENDERED_LENGTH, ((DocumentValue.Text) artifact
                        .member(ArtifactDescriptor.DIGEST).orElseThrow()).value().length(),
                "the reference carries no digest a reader could verify the bytes against");
    }

    @Test
    @DisplayName("a node's children are answered in the order a reader can walk them")
    void childrenAreAnsweredInAscendingOrder() throws RepositoryException {
        // The order is the answer's own, not the repository's iteration order:
        // a reader rebuilding a tree from this document needs the children in a
        // sequence it can rely on, and what a repository hands back is whatever
        // its storage happens to hold.
        final javax.jcr.Node parent = nodeAt("/content/ordered");
        parent.addNode("beta", "nt:unstructured");
        parent.addNode("alpha", "nt:unstructured");
        parent.addNode("gamma", "nt:unstructured");
        session().save();
        final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/ordered", 1), "the subtree was refused");
        final DocumentValue.Mapping document = (DocumentValue.Mapping) produced.result()
                .member(LoadContentResult.DOCUMENT).orElseThrow();
        assertEquals(List.of(
                        "/content/ordered/alpha",
                        "/content/ordered/beta",
                        "/content/ordered/gamma"),
                childPaths(document),
                "a reader rebuilding the tree would put the children back in another order");
    }

    @Test
    @DisplayName("a walk that stopped at its depth says so rather than saying it truncated")
    void awalkAtItsDepthSaysItTruncated() throws RepositoryException {
        nodeAt("/content/deep").addNode("child", "nt:unstructured")
                .addNode("grandchild", "nt:unstructured");
        session().save();
        final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/deep", 0), "the subtree was refused");
        final DocumentValue.Mapping document = (DocumentValue.Mapping) produced.result()
                .member(LoadContentResult.DOCUMENT).orElseThrow();
        assertEquals(DocumentValue.Truth.TRUE, truncatedIn(document),
                "a walk that stopped at its depth left children out and did not say so");
        assertTrue(childPaths(document).isEmpty(),
                "a walk asked for no generations below the node carried some anyway");
    }

    private static List<String> childPaths(DocumentValue.Mapping document) {
        final DocumentValue.Sequence children = (DocumentValue.Sequence) document
                .member(LoadContentResult.CHILDREN).orElseThrow();
        final List<String> paths = new ArrayList<>();
        for (final DocumentValue child : children.items()) {
            final DocumentValue.Mapping held = (DocumentValue.Mapping) child;
            paths.add(((DocumentValue.Text) held.member(LoadContentResult.PATH).orElseThrow())
                    .value());
        }
        return paths;
    }

    private static DocumentValue.Truth truncatedIn(DocumentValue.Mapping document) {
        return ((DocumentValue.Flag) document
                .member(LoadContentResult.CHILDREN_TRUNCATED).orElseThrow()).value();
    }

    private static DigestValue digest() {
        return assertInstanceOf(DigestValue.Held.class,
                DigestValue.of("d".repeat(DigestValue.RENDERED_LENGTH)),
                "the digest was refused").digest();
    }

    private CommandHandler.Answer run(String path, long depth) {
        return new LoadContentHandler().run(argument(path, depth), readOnly(), context());
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT, CONTRACT.value(
                        rs.slingshot.agent.contract.ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
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

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    static {
        assertTrue(List.of().isEmpty());
    }
}
