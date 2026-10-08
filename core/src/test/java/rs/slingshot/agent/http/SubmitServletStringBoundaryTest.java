// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.ServletException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.repository.AgentSession;

/**
 * The submission envelope carries canonical arguments as one string, under their own byte bound.
 *
 * <p>The runtime deliberately serves no command. Its selection call witnesses that the entire
 * envelope and identity were read, without admitting or executing a content operation. Ordinary
 * strings, including a nested member with the same name, must retain the general document bound.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class SubmitServletStringBoundaryTest {
    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String PERMITTED_GROUP = "administrators";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @BeforeEach
    void permitCaller() throws RepositoryException {
        new AgentSession().available(sling.getService(ResourceResolverFactory.class));
        MockOsgi.activate(new AuthorizationGate(), MockOsgi.newBundleContext(),
                java.util.Map.of("permitted.groups", new String[] { PERMITTED_GROUP }));
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class));
        final var users = ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager();
        final var existing = users.getAuthorizable(PERMITTED_GROUP);
        final var group = existing == null ? users.createGroup(PERMITTED_GROUP)
                : (org.apache.jackrabbit.api.security.user.Group) existing;
        group.addMember(java.util.Objects.requireNonNull(users.getAuthorizable(session.getUserID())));
        session.save();
    }

    @AfterEach
    void revokeCaller() {
        new AgentSession().stopped();
        new AuthorizationGate().stopped();
    }

    @Test
    void aMaximumInlinePayloadReachesRuntimeSelectionInsideItsEnvelope()
            throws IOException, ServletException {
        final byte[] payload = new byte[Math.toIntExact(
                CONTRACT.value(ContractLimit.MAXIMUM_INLINE_BINARY_DECODED_BYTES))];
        final String encoded = Base64.getEncoder().encodeToString(payload);
        assertEquals(CONTRACT.value(ContractLimit.MAXIMUM_INLINE_BINARY_ENCODED_BYTES),
                encoded.length());
        final String arguments = "{\"name\":\"synthetic-envelope-asset\",\"parent_path\":\"/content/dam\","
                + "\"payload\":{\"encoded_content\":\"" + encoded
                + "\",\"media_type\":\"application/octet-stream\"}}";
        assertTrue(arguments.length() > CONTRACT.value(ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES));
        assertTrue(arguments.length() < CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_ARGUMENT_BYTES));
        assertEquals(1, selected(withArguments(arguments)));
    }

    @Test
    void canonicalArgumentsAtTheirOwnByteBoundReachRuntimeSelection()
            throws IOException, ServletException {
        final String arguments = "x".repeat(Math.toIntExact(
                CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_ARGUMENT_BYTES)));
        assertEquals(1, selected(withArguments(arguments)));
    }

    @Test
    void canonicalArgumentsOneBytePastTheirBoundNeverReachRuntimeSelection()
            throws IOException, ServletException {
        final String arguments = "x".repeat(Math.toIntExact(
                CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_ARGUMENT_BYTES) + 1));
        assertEquals(0, selected(withArguments(arguments)));
    }

    @Test
    void argumentBytesCountDecodedUtf8RatherThanCharacters()
            throws IOException, ServletException {
        final int bound = Math.toIntExact(CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_ARGUMENT_BYTES));
        final String scalar = "\u00e9";
        final String arguments = scalar.repeat(bound / scalar.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(bound, arguments.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(1, selected(withArguments(arguments)));
        assertEquals(0, selected(withArguments(arguments + "x")));
    }

    @Test
    void anotherRootMemberRetainsTheOrdinaryStringBound() throws IOException, ServletException {
        final var submission = withArguments("{}");
        submission.put("synthetic_extra", new DocumentValue.Text(oversizedOrdinaryString()));
        assertEquals(0, selected(submission));
    }

    @Test
    void aNestedArgumentMemberRetainsTheOrdinaryStringBound() throws IOException, ServletException {
        final var submission = withArguments("{}");
        final SequencedMap<String, DocumentValue> nested = new LinkedHashMap<>();
        nested.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text(oversizedOrdinaryString()));
        submission.put("synthetic_extra", new DocumentValue.Mapping(nested));
        assertEquals(0, selected(submission));
    }

    @Test
    void aMemberNameRetainsTheOrdinaryStringBound() throws IOException, ServletException {
        final var submission = withArguments("{}");
        submission.put(oversizedOrdinaryString(), new DocumentValue.Text("short"));
        assertEquals(0, selected(submission));
    }

    private static String oversizedOrdinaryString() {
        return "x".repeat(Math.toIntExact(CONTRACT.value(ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES) + 1));
    }

    private static SequencedMap<String, DocumentValue> withArguments(String arguments) {
        final var submission = new LinkedHashMap<>(fixture().members());
        submission.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text(arguments));
        submission.put("request_start_unix_milliseconds",
                new DocumentValue.Whole(System.currentTimeMillis()));
        return submission;
    }

    private int selected(SequencedMap<String, DocumentValue> submission)
            throws IOException, ServletException {
        final var request = new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(new DocumentValue.Mapping(submission))).bytes());
        ((MockRequestPathInfo) request.getRequestPathInfo()).setResourcePath(SubmitServlet.route().path());
        final var response = new MockSlingHttpServletResponse();
        final var selection = new Selection(new AtomicInteger());
        new SubmitServlet(selection).service(request, response);
        assertEquals(SubmitServlet.REFUSED, response.getStatus());
        assertEquals("", response.getOutputAsString());
        return selection.calls().get();
    }

    private record Selection(AtomicInteger calls) implements SubmitServlet.Commands {
        @Override
        public boolean serves(String wireName) {
            assertEquals("query_paths", wireName);
            calls.incrementAndGet();
            return false;
        }

        @Override
        public ExecutionOutcome.Completion run(LogicalOperation operation,
                                                DocumentValue.Mapping submission, Session session) {
            throw new IllegalStateException("this suite must never admit or execute a command");
        }
    }

    private static DocumentValue.Mapping fixture() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("policy"))) {
            root = root.getParent();
        }
        final Path file = java.util.Objects.requireNonNull(root).resolve(
                "core/src/test/resources/fixtures/submit-servlet/a-submission.json");
        try {
            return assertInstanceOf(DocumentValue.Mapping.class,
                    assertInstanceOf(BoundedDocumentReader.Read.class, BoundedDocumentReader.read(
                            Files.readAllBytes(file), BoundedDocumentReader.Bounds.from(CONTRACT))).value());
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the synthetic submission fixture is unreadable", unreadable);
        }
    }
}
