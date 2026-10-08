// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeTypeTemplate;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/** Client-serialized predicates and client-model answers against real typed Oak properties. */
@ExtendWith(SlingContextExtension.class)
final class ClientPredicateCorpusTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String ROOT = "/content/synthetic";
    private static final String PROPERTY = "synthetic_property";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void queryDiscoveryAgreesWithTheClientModel() throws IOException, RepositoryException {
        prepare();
        assertAll(corpus().stream().map(row -> () -> verify(row, QueryPathsCommand.WIRE_NAME)));
    }

    @Test
    void assetDiscoveryAgreesWithTheClientModel() throws IOException, RepositoryException {
        prepare();
        assertAll(corpus().stream().map(row -> () -> verify(row, FindAssetsByMetadataCommand.WIRE_NAME)));
    }

    private void verify(DocumentValue.Mapping row, String command) throws RepositoryException {
        plant(mapping(row, "observation"));
        try (DiscoveryRegistry discovery = new DiscoveryRegistry(CONTRACT)) {
            final CommandHandler handler = QueryPathsCommand.WIRE_NAME.equals(command)
                    ? new QueryPathsHandler(CONTRACT, discovery)
                    : new FindAssetsByMetadataHandler(CONTRACT, discovery);
            final String name = text(row, "case") + " " + command;
            final DocumentValue.Mapping result = assertInstanceOf(CommandHandler.Produced.class,
                    handler.run(mapping(row, "arguments"),
                            PageSearchTestSupport.readOnly(sling.resourceResolver()), context()), name)
                    .result();
            final var matches = assertInstanceOf(DocumentValue.Sequence.class,
                    result.member("matches").orElseThrow(), name);
            final boolean expected = row.member("expected").orElseThrow()
                    .equals(new DocumentValue.Flag(DocumentValue.Truth.TRUE));
            assertEquals(expected ? 1 : 0, matches.items().size(), name);
        }
    }

    private void plant(DocumentValue.Mapping observed) throws RepositoryException {
        final Node node = session().getNode(ROOT);
        if (node.hasProperty(PROPERTY)) {
            node.getProperty(PROPERTY).remove();
        }
        switch (text(observed, "state")) {
            case "absent" -> { }
            case "empty_multiple" -> node.setProperty(PROPERTY, new Value[0],
                    type(text(observed, "type")));
            default -> write(node, mapping(observed, "value"));
        }
        session().save();
    }

    private void write(Node node, DocumentValue.Mapping property) throws RepositoryException {
        if ("single".equals(text(property, "cardinality"))) {
            node.setProperty(PROPERTY, value(mapping(property, "value")));
            return;
        }
        final var written = (DocumentValue.Sequence) property.member("values").orElseThrow();
        final Value[] values = written.items().stream().map(DocumentValue.Mapping.class::cast)
                .map(this::value).toArray(Value[]::new);
        node.setProperty(PROPERTY, values, values[0].getType());
    }

    private Value value(DocumentValue.Mapping scalar) {
        final DocumentValue held = scalar.member("value").orElseThrow();
        try {
            final var factory = session().getValueFactory();
            return switch (text(scalar, "type")) {
                case "boolean" -> factory.createValue(held.equals(
                        new DocumentValue.Flag(DocumentValue.Truth.TRUE)));
                case "integer" -> factory.createValue(Long.parseLong(((DocumentValue.Text) held).value()));
                case "decimal" -> factory.createValue(new BigDecimal(((DocumentValue.Text) held).value()));
                case "date_time" -> factory.createValue(date(((DocumentValue.Text) held).value()));
                case "repository_path" -> factory.createValue(((DocumentValue.Text) held).value(),
                        PropertyType.PATH);
                default -> factory.createValue(((DocumentValue.Text) held).value());
            };
        } catch (final RepositoryException failure) {
            throw new AssertionError("synthetic property could not be planted", failure);
        }
    }

    private static Calendar date(String text) {
        final var result = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        result.setTimeInMillis(Instant.parse(text).toEpochMilli());
        return result;
    }

    private static int type(String kind) {
        return switch (kind) {
            case "boolean" -> PropertyType.BOOLEAN;
            case "integer" -> PropertyType.LONG;
            case "decimal" -> PropertyType.DECIMAL;
            case "date_time" -> PropertyType.DATE;
            case "repository_path" -> PropertyType.PATH;
            default -> PropertyType.STRING;
        };
    }

    private void prepare() throws RepositoryException {
        final Session held = session();
        final var namespaces = held.getWorkspace().getNamespaceRegistry();
        if (!List.of(namespaces.getPrefixes()).contains("dam")) {
            namespaces.registerNamespace("dam", "https://synthetic.invalid/asset");
        }
        final var manager = held.getWorkspace().getNodeTypeManager();
        if (!manager.hasNodeType("dam:Asset")) {
            final NodeTypeTemplate type = manager.createNodeTypeTemplate();
            type.setName("dam:Asset");
            type.setDeclaredSuperTypeNames(new String[] {"nt:unstructured"});
            manager.registerNodeType(type, false);
        }
        held.getRootNode().addNode("content", "nt:unstructured").addNode("synthetic", "dam:Asset");
        held.save();
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private static List<DocumentValue.Mapping> corpus() throws IOException {
        try (InputStream stream = Objects.requireNonNull(ClientPredicateCorpusTest.class
                .getResourceAsStream("/fixtures/client-predicate-corpus.json"))) {
            final var read = assertInstanceOf(BoundedDocumentReader.Read.class,
                    BoundedDocumentReader.read(new String(stream.readAllBytes(),
                            StandardCharsets.UTF_8).stripTrailing().getBytes(StandardCharsets.UTF_8),
                            new BoundedDocumentReader.Bounds(2_000_000, 20, 20, 4096)));
            final var sequence = assertInstanceOf(DocumentValue.Sequence.class, read.value());
            assertEquals(3110, sequence.items().size());
            return sequence.items().stream().map(DocumentValue.Mapping.class::cast).toList();
        }
    }

    private static String text(DocumentValue.Mapping mapping, String member) {
        return ((DocumentValue.Text) mapping.member(member).orElseThrow()).value();
    }

    private static DocumentValue.Mapping mapping(DocumentValue.Mapping mapping, String member) {
        return (DocumentValue.Mapping) mapping.member(member).orElseThrow();
    }

    private static CallerContext context() {
        final var operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT)).identifier();
        return PageSearchTestSupport.context(CONTRACT, operation, Budget.discovery(CONTRACT).limit());
    }
}
