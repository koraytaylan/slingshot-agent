// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jcr.Node;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.StatePath;

/** Covers the servlet runtime's active and fail-closed lifecycle. */
final class DefaultCommandRuntimeTest {

    private static final Path FIXTURES = repositoryRoot()
            .resolve("core/src/test/resources/fixtures/command-registry/accepted");
    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "test is not inside repository");
    }

    @Test
    void activeRuntimeDispatchesOnlyDeclaredNamesAndFailsClosedOnMalformedSubmission() {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                if ("query_paths".equals(row.wireName())) {
                    return new Failed(row.failureCategories().getFirst(), "test failure");
                }
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final CommandDispatch dispatch = assertInstanceOf(CommandDispatch.Held.class,
                CommandDispatch.of(registry, handlers)).dispatch();
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(dispatch, CONTRACT);
        assertTrue(runtime.serves(registry.wireNames().getFirst()));
        assertFalse(runtime.serves("not-a-command"));
        final DocumentValue.Mapping empty = new DocumentValue.Mapping(new LinkedHashMap<>());
        assertInstanceOf(ExecutionOutcome.Uncertain.class, runtime.run(null, empty, null));
        assertInstanceOf(ExecutionOutcome.Uncertain.class,
                runtime.run(null, empty, null, null, null));
    }

    @Test
    void pagingIsBoundToTheOperationsTargetAndGeneration() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        final LogicalOperation operation = operation();
        final CallerContext.Available paging = assertInstanceOf(CallerContext.Available.class,
                runtime.paging(operation, CONTRACT, null));
        assertEquals(operation.identity().targetDigest(), paging.targetDigest());
        assertEquals(operation.identity().generation(), paging.generation());
    }

    @Test
    void activeRuntimeRunsAValidSubmissionThroughTheDispatch() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        final AtomicBoolean fail = new AtomicBoolean(true);
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                if ("query_paths".equals(row.wireName()) && fail.getAndSet(false)) {
                    return new Failed(row.failureCategories().getFirst(), "test failure");
                }
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        final CommandHandler.Artifact offered = new CommandHandler.Artifact(
                new DocumentValue.Mapping(new LinkedHashMap<>()), "bad/slot",
                new byte[] {1, 2, 3});
        assertTrue(java.util.Arrays.equals(new byte[] {1, 2, 3}, offered.bytes()));
        final LogicalOperation operation = operation();
        final SequencedMap<String, DocumentValue> submissionMembers = new LinkedHashMap<>();
        submissionMembers.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{}"));
        final DocumentValue.Mapping submission = new DocumentValue.Mapping(submissionMembers);
        assertInstanceOf(ExecutionOutcome.Completion.class,
                runtime.run(operation, submission, null, null, context()));
        assertInstanceOf(ExecutionOutcome.Succeeded.class,
                runtime.run(operation, submission, null, null));
        final SequencedMap<String, DocumentValue> malformedMembers = new LinkedHashMap<>();
        malformedMembers.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{"));
        assertInstanceOf(ExecutionOutcome.Uncertain.class,
                runtime.run(operation, new DocumentValue.Mapping(malformedMembers), null, null,
                        context()));
    }

    @Test
    void aCommandRefusalDocumentReachesTheWireAndItsAbsenceFallsBackToTheCategory()
            throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final String category = registry.row("query_paths").orElseThrow()
                .failureCategories().getFirst();
        final SequencedMap<String, DocumentValue> refusal = new LinkedHashMap<>();
        refusal.put(rs.slingshot.agent.wire.CommandFailure.CATEGORY,
                new DocumentValue.Text(category));
        refusal.put("target_path", new DocumentValue.Text("/content/site/article"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{}"));
        final DocumentValue.Mapping submission = new DocumentValue.Mapping(members);

        final ExecutionOutcome.Failed stated = assertInstanceOf(ExecutionOutcome.Failed.class,
                failureRuntime(registry, new CommandHandler.Stated(new DocumentValue.Mapping(refusal)))
                        .run(operation(), submission, null, null, context()),
                "a failure carrying its own refusal document did not reach the caller as one");
        assertInstanceOf(ExecutionOutcome.Inline.class, stated.result());
        final String carried = ((ExecutionOutcome.Inline) stated.result()).document();
        assertTrue(carried.contains("/content/site/article"),
                "the command's own refusal document did not reach the wire: " + carried);

        // The other case: a failure that names no document of its own is rendered as the command's
        // own refusal document, which is what the client authenticates an ending against. A bare
        // category is not that document, and the shared category shape is not either.
        final ExecutionOutcome.Failed unnamed = assertInstanceOf(ExecutionOutcome.Failed.class,
                failureRuntime(registry, new CommandHandler.Unstated())
                        .run(operation(), submission, null, null, context()),
                "a failure naming no refusal document did not reach the caller as a failure");
        final String rendered = ((ExecutionOutcome.Inline) unnamed.result()).document();
        assertTrue(rendered.contains(category),
                "the rendered refusal did not carry the declared category: " + rendered);
        assertTrue(rendered.contains("\"failure\""),
                "the rendered refusal is not the command's own closed shape: " + rendered);
    }

    @Test
    void anUnstatedRefusalIsRenderedAsTheClientsOwnDocumentWithTheRequestsCorrelation()
            throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SubmitServlet.ARGUMENTS,
                new DocumentValue.Text("{\"root_path\":\"/content/site/en\"}"));
        final ExecutionOutcome.Failed rendered = assertInstanceOf(ExecutionOutcome.Failed.class,
                updatePageRuntime(registry).run(operationFor("list_child_pages"),
                        new DocumentValue.Mapping(members), null, null, context()),
                "a child listing refused as root not found did not produce a document");
        final String document = ((ExecutionOutcome.Inline) rendered.result()).document();
        assertTrue(document.contains("\"failure\":\"root_not_found\""),
                "the refusal did not carry the category the handler declared: " + document);
        assertTrue(document.contains("\"root_path\":\"/content/site/en\""),
                "the refusal did not echo the anchor the caller named: " + document);
    }

    /** A runtime whose every handler fails as root not found, the shape a discovery refusal has. */
    private DefaultCommandRuntime updatePageRuntime(CommandRegistry registry) {
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return new Failed("root_not_found", "test failure");
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        return new DefaultCommandRuntime(assertInstanceOf(CommandDispatch.Held.class,
                CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
    }

    private DefaultCommandRuntime failureRuntime(CommandRegistry registry,
                                                 CommandHandler.RefusalDocument refusal) {
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return new Failed(row.failureCategories().getFirst(), "test failure", refusal);
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        return new DefaultCommandRuntime(assertInstanceOf(CommandDispatch.Held.class,
                CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
    }

    @Test
    void artifactWithAnInvalidSlotFailsClosedBeforeRepositoryAccess() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return "query_paths".equals(row.wireName())
                        ? new Artifact(new DocumentValue.Mapping(new LinkedHashMap<>()), "bad/slot",
                                new byte[] {1, 2, 3})
                        : new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        final LogicalOperation operation = operation();
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{}"));
        assertInstanceOf(ExecutionOutcome.Uncertain.class,
                runtime.run(operation, new DocumentValue.Mapping(members), null, null, context()));
    }

    @Test
    void serializationTemporarilyRemovesPlatformRuntimeState() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
             java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(runtime);
        }
        assertTrue(runtime.serves(registry.wireNames().getFirst()));
    }

    @Test
    void dsActivationAdvertisesOnlyThePackagedStatelessSubset() {
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime();
        runtime.activate();
        assertTrue(runtime.serves("query_paths"));
        assertFalse(runtime.serves("download_content_package"));
        runtime.deactivate();
        assertFalse(runtime.serves("query_paths"));
    }

    @Test
    void aplatformCommandIsAdvertisedExactlyWhileItsAdapterIsBound() {
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime();
        runtime.activate();
        assertTrue(runtime.serves("list_group_members"),
                "the user and group commands need no adapter and were not advertised");
        assertFalse(runtime.serves("find_sling_jobs"),
                "a job command was advertised with nothing bound to answer it");
        assertFalse(runtime.serves("replicate_content"));
        final Class<?>[] seam = {rs.slingshot.agent.command.platform.JobInventory.class};
        final rs.slingshot.agent.command.platform.JobInventory inventory =
                (rs.slingshot.agent.command.platform.JobInventory) java.lang.reflect.Proxy
                        .newProxyInstance(Thread.currentThread().getContextClassLoader(), seam,
                                (proxy, method, arguments) -> {
                                    throw new UnsupportedOperationException(method.getName());
                                });
        final rs.slingshot.agent.command.platform.ContentAdmission admission =
                (paths, session) -> new rs.slingshot.agent.command.platform.ContentAdmission
                        .Admitted(paths.size());
        final Class<?>[] engine = {rs.slingshot.agent.command.platform.WorkflowService.class};
        final rs.slingshot.agent.command.platform.WorkflowService workflows =
                (rs.slingshot.agent.command.platform.WorkflowService) java.lang.reflect.Proxy
                        .newProxyInstance(Thread.currentThread().getContextClassLoader(), engine,
                                (proxy, method, arguments) -> {
                                    throw new UnsupportedOperationException(method.getName());
                                });
        final rs.slingshot.agent.command.platform.BundleInventory bundles =
                unanswering(rs.slingshot.agent.command.platform.BundleInventory.class);
        final rs.slingshot.agent.command.platform.ReplicationInventory agents =
                unanswering(rs.slingshot.agent.command.platform.ReplicationInventory.class);
        final rs.slingshot.agent.command.platform.ConfigurationCatalogues configurations =
                unanswering(rs.slingshot.agent.command.platform.ConfigurationCatalogues.class);
        runtime.deactivate();
        runtime.jobsAvailable(inventory);
        runtime.admissionAvailable(admission);
        runtime.workflowsAvailable(workflows);
        runtime.bundlesAvailable(bundles);
        runtime.agentsAvailable(agents);
        runtime.configurationsAvailable(configurations);
        runtime.activate();
        assertTrue(runtime.serves("list_open_service_gateway_initiative_bundles"));
        assertTrue(runtime.serves("flush_replication_queue"));
        assertTrue(runtime.serves("inspect_open_service_gateway_initiative_configuration"));
        assertTrue(runtime.serves("start_workflow"));
        assertTrue(runtime.serves("set_workflow_instance_suspension"));
        assertTrue(runtime.serves("find_sling_jobs"));
        assertTrue(runtime.serves("cancel_sling_job"));
        assertTrue(runtime.serves("replicate_content"));
        runtime.deactivate();
        runtime.jobsUnavailable(inventory);
        runtime.admissionUnavailable(admission);
        runtime.workflowsUnavailable(workflows);
        runtime.bundlesUnavailable(bundles);
        runtime.agentsUnavailable(agents);
        runtime.configurationsUnavailable(configurations);
        runtime.activate();
        assertFalse(runtime.serves("list_workflow_models"));
        assertFalse(runtime.serves("list_open_service_gateway_initiative_components"));
        assertFalse(runtime.serves("list_replication_agents"));
        assertFalse(runtime.serves("find_open_service_gateway_initiative_configurations"));
        assertFalse(runtime.serves("find_sling_jobs"),
                "a job command stayed advertised after its adapter went away");
        assertFalse(runtime.serves("replicate_content"));
        runtime.deactivate();
    }

    @Test
    void thepackageBuildIsAdvertisedWhereverThereIsSomewhereToStage() throws java.io.IOException {
        final Path area = Files.createTempDirectory("slingshot-staging");
        try {
            final DefaultCommandRuntime runtime = new DefaultCommandRuntime();
            runtime.activated(context(area.resolve("staging").toFile()));
            assertTrue(runtime.serves("download_content_package"),
                    "a bundle with a data area did not advertise the package build");
            runtime.deactivate();
            final DefaultCommandRuntime without = new DefaultCommandRuntime();
            without.activated(context(null));
            assertTrue(without.serves("download_content_package"),
                    "a framework keeping no data area left the package build unadvertised");
            without.deactivate();
            final DefaultCommandRuntime unstaged = new DefaultCommandRuntime();
            unstaged.activate();
            assertFalse(unstaged.serves("download_content_package"),
                    "a runtime given nowhere to stage advertised the package build");
            assertTrue(unstaged.serves("query_paths"));
            unstaged.deactivate();
        } finally {
            try (var walked = Files.walk(area)) {
                walked.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    private static org.osgi.framework.BundleContext context(java.io.File data) {
        return (org.osgi.framework.BundleContext) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {org.osgi.framework.BundleContext.class},
                (proxy, method, arguments) -> data);
    }

    private static <T> T unanswering(Class<T> seam) {
        return seam.cast(java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {seam},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static LogicalOperation operation() throws java.io.IOException {
        final Path fixture = repositoryRoot().resolve(
                "core/src/test/resources/fixtures/submit-servlet/a-submission.json");
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(Files.readAllBytes(fixture),
                        BoundedDocumentReader.Bounds.from(CONTRACT))).value());
        final OperationIdentity identity = assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document.member("operation").orElseThrow(), CONTRACT)).identity();
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final rs.slingshot.agent.identity.CommandContractIdentity contract =
                assertInstanceOf(rs.slingshot.agent.identity.CommandContractIdentity.Held.class,
                        registry.row("query_paths").orElseThrow().identity(
                                rs.slingshot.agent.identity.CommandContractIdentity.Bounds.from(CONTRACT)))
                        .identity();
        final StatePath.Caller caller = assertInstanceOf(StatePath.Held.class,
                StatePath.caller("admin")).caller();
        return assertInstanceOf(LogicalOperation.Held.class, LogicalOperation.accepted(identity,
                Digest.of("submission".getBytes(StandardCharsets.UTF_8)), contract, caller,
                1_000L, 1_000L, CONTRACT)).operation();
    }

    /** The same operation, under one named command's own contract identity. */
    private static LogicalOperation operationFor(String wireName) throws java.io.IOException {
        final Path fixture = repositoryRoot().resolve(
                "core/src/test/resources/fixtures/submit-servlet/a-submission.json");
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(Files.readAllBytes(fixture),
                        BoundedDocumentReader.Bounds.from(CONTRACT))).value());
        final OperationIdentity identity = assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document.member("operation").orElseThrow(), CONTRACT)).identity();
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final rs.slingshot.agent.identity.CommandContractIdentity contract =
                assertInstanceOf(rs.slingshot.agent.identity.CommandContractIdentity.Held.class,
                        registry.row(wireName).orElseThrow().identity(
                                rs.slingshot.agent.identity.CommandContractIdentity.Bounds.from(CONTRACT)))
                        .identity();
        final StatePath.Caller caller = assertInstanceOf(StatePath.Held.class,
                StatePath.caller("admin")).caller();
        return assertInstanceOf(LogicalOperation.Held.class, LogicalOperation.accepted(identity,
                Digest.of("submission".getBytes(StandardCharsets.UTF_8)), contract, caller,
                1_000L, 1_000L, CONTRACT)).operation();
    }

    private static CallerContext context() throws java.io.IOException {
        return new CallerContext(operation().identity().identifier(),
                rs.slingshot.agent.command.Budget.discovery(CONTRACT),
                rs.slingshot.agent.command.Budget.time(CONTRACT),
                rs.slingshot.agent.command.Budget.result(
                        assertInstanceOf(CommandRegistry.Loaded.class,
                                CommandRegistry.read(FIXTURES)).registry().row("query_paths")
                                .orElseThrow()),
                rs.slingshot.agent.command.ProgressSink.under(CONTRACT));
    }

    /**
     * The runtime's own artifact path, driven on a real repository.
     *
     * <p>Kept in its own class because it needs a session the lifecycle suite does not, and a
     * handler whose answer is published is the one route through this runtime that writes an
     * artifact: a slot the store will take, the bytes the command produced, and a reference the
     * caller fetches by.</p>
     */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.extension.ExtendWith(SlingContextExtension.class)
    final class ArtifactAnswers {

        private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

        @Test
        void anArtifactAnswerIsPublishedAndAnsweredWithAReference()
                throws javax.jcr.RepositoryException, java.io.IOException {
            final Session session = prepared();
            final LogicalOperation operation = operation();
            final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                    assertInstanceOf(CommandDispatch.Held.class, CommandDispatch.of(
                            registry(), artifactHandlers())).dispatch(), CONTRACT);
            final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
            members.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{}"));
            final ExecutionOutcome.Succeeded succeeded = assertInstanceOf(
                    ExecutionOutcome.Succeeded.class,
                    runtime.run(operation, new DocumentValue.Mapping(members), session,
                            sling.resourceResolver(), context()),
                    "an artifact answer was not published");
            final ExecutionOutcome.Published published = assertInstanceOf(
                    ExecutionOutcome.Published.class, succeeded.result(),
                    "the answer is not a reference to what was published");
            assertEquals("query_paths", published.slot().name());
            assertInstanceOf(ExecutionOutcome.Written.class, published.canonicalResult(),
                    "the command's own document was not carried beside the reference");
            assertTrue(published.byteCount() > 0, "nothing was published");
            try (java.io.InputStream held = rs.slingshot.agent.store.ArtifactStore.open(session,
                    rs.slingshot.agent.execution.OperationStore.pathOf(operation.identity()),
                    published.slot()).orElseThrow(
                            () -> new AssertionError("the published artifact is not there"))) {
                assertTrue(java.util.Arrays.equals(ARTIFACT_BYTES, held.readAllBytes()),
                        "what a caller fetches is not what the command produced");
            }
        }

        private static final byte[] ARTIFACT_BYTES = "an overflowing answer".getBytes(
                StandardCharsets.UTF_8);

        private SequencedMap<String, CommandHandler> artifactHandlers() throws java.io.IOException {
            final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
            registry().rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
                @Override
                public Answer run(DocumentValue.Mapping arguments,
                                  org.apache.sling.api.resource.ResourceResolver resolver,
                                  CallerContext context) {
                    if (!"query_paths".equals(row.wireName())) {
                        return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
                    }
                    final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
                    result.put(rs.slingshot.agent.command.ArtifactDescriptor.SLOT,
                            new DocumentValue.Text("query_paths"));
                    result.put(rs.slingshot.agent.command.ArtifactDescriptor.BYTE_LENGTH,
                            new DocumentValue.Whole(ARTIFACT_BYTES.length));
                    return new Artifact(new DocumentValue.Mapping(result), "query_paths",
                            ARTIFACT_BYTES);
                }

                @Override
                public List<String> categories() {
                    return row.failureCategories();
                }
            }));
            return handlers;
        }

        private Session prepared() throws javax.jcr.RepositoryException, java.io.IOException {
            final Session session = java.util.Objects.requireNonNull(
                    sling.resourceResolver().adaptTo(Session.class),
                    "the resolver has no session, which is a repository that did not start");
            walked(session, rs.slingshot.agent.store.StatePath.ROOT);
            GenerationStore.establish(session);
            LedgerAdmission.prepare(session, caller());
            rs.slingshot.agent.store.ArtifactStore.prepare(session, caller());
            walked(session, rs.slingshot.agent.store.StatePath.deployment(
                    rs.slingshot.agent.store.StatePath.OPERATIONS).path());
            walked(session, rs.slingshot.agent.execution.OperationStore.pathOf(
                    operation().identity()).path());
            session.save();
            return session;
        }

        private static void walked(Session session, String path) throws javax.jcr.RepositoryException {
            Node node = session.getRootNode();
            for (final String segment : path.substring(1).split("/")) {
                node = node.hasNode(segment) ? node.getNode(segment)
                        : node.addNode(segment, "nt:unstructured");
            }
            session.save();
        }

        private static StatePath.Caller caller() {
            return assertInstanceOf(StatePath.Held.class,
                    StatePath.caller("admin"), "the caller was refused").caller();
        }

        private static CommandRegistry registry() throws java.io.IOException {
            return assertInstanceOf(CommandRegistry.Loaded.class,
                    CommandRegistry.read(FIXTURES)).registry();
        }

        private static LogicalOperation operation() throws java.io.IOException {
            return DefaultCommandRuntimeTest.operation();
        }

        private static CallerContext context() throws java.io.IOException {
            return DefaultCommandRuntimeTest.context();
        }
    }
}
