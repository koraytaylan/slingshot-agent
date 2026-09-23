// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.zip.ZipInputStream;
import javax.jcr.Session;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import rs.slingshot.agent.command.ArtifactDescriptor;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.StagingArea;
import rs.slingshot.agent.command.StagingRooms;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The one read that needs room to work, and stays a read.
 *
 * <p>What is proved here: the staging is gone on every path, including the ones that failed; the
 * handler obtains no session and writes nothing through the caller's resolver; a filter selecting
 * more than may be evaluated is refused <em>before</em> anything is staged; and the filter reported
 * back is the one the package was actually built with.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class DownloadContentPackageCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("the staging is gone after a package is built, and after one that failed")
    void thestagingIsGoneOnEveryPath(@TempDir Path under) {
        corpus();
        // Closed here as well as by the handler, because closing a place that is already gone is
        // what closing asked for — and a suite that relied on the handler having closed it would
        // leave the staging behind on the day the handler stopped doing so.
        try (StagingArea built = staging(under.resolve("built"))) {
            assertInstanceOf(CommandHandler.Artifact.class,
                    new DownloadContentPackageHandler(CONTRACT, rooms(built))
                            .run(argument(List.of("/content/site")), readOnly(),
                                    context()),
                    "the package was refused");
            assertTrue(!built.isOpen(),
                    "the staging is still open after a package was built. A package that built"
                            + " and left its staging behind is a repository that fills up"
                            + " quietly.");
        }
        try (StagingArea failed = staging(under.resolve("failed"))) {
            assertInstanceOf(CommandHandler.Failed.class,
                    new DownloadContentPackageHandler(CONTRACT, rooms(failed))
                            .run(argument(List.of("/content/nothing-is-here")),
                                    readOnly(), context()),
                    "a root that is not there was packaged");
            assertEquals(row().stagingBytes(), failed.remaining(),
                    "a build that failed before it staged anything has spent room in the agent's"
                            + " own tree, so a caller whose root was mistyped costs storage");
        }
    }

    @Test
    @DisplayName("a run with nowhere to work is refused rather than packaging into nothing")
    void arunWithNowhereToWorkIsRefused() {
        corpus();
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new DownloadContentPackageHandler(CONTRACT, Optional::empty)
                        .run(argument(List.of("/content/site")), readOnly(),
                                context()),
                "a package was reported built by a run that had nowhere to build it");
        assertEquals(DownloadContentPackageHandler.PACKAGE_FAILED, failed.category());
        assertTrue(failed.detail().contains("nowhere to work"), failed.detail());
    }

    @Test
    @DisplayName("the handler obtains no session and writes nothing through the caller's resolver")
    void thehandlerObtainsNothing(@TempDir Path under) {
        corpus();
        // Each readOnly() is a fresh read-only view over the one session this test's context owns,
        // so what the last of them reports about pending changes is what the handler's own view
        // did. The views are not held in a local because closing one would close that shared
        // session, and the context is the thing that owns closing it.
        assertEquals(null, readOnly().adaptTo(Session.class),
                "the resolver handed to this handler can reach a session, and this command is not"
                        + " the exception to a handler obtaining nothing");
        try (StagingArea room = staging(under)) {
            assertInstanceOf(CommandHandler.Artifact.class,
                    new DownloadContentPackageHandler(CONTRACT, rooms(room))
                            .run(argument(List.of("/content/site")), readOnly(),
                                    context()));
        }
        assertTrue(!readOnly().hasChanges(),
                "the caller's own resolver holds changes after a read ran through it");
    }

    @Test
    @DisplayName("a filter selecting more than may be evaluated is refused before anything is staged")
    void anoversizedFilterIsRefusedBeforeStaging(@TempDir Path under) {
        corpus();
        try (StagingArea room = staging(under)) {
            final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                    new DownloadContentPackageHandler(CONTRACT, rooms(room))
                            .run(argument(List.of("/content/site")), readOnly(),
                                    narrowContext()),
                    "a filter past the evaluation budget was built anyway");
            assertEquals(DownloadContentPackageHandler.EVALUATION_BUDGET_EXCEEDED,
                    failed.category());
            assertTrue(failed.detail().contains("before anything is staged"), failed.detail());
            assertTrue(room.isOpen(),
                    "the staging was opened for a filter refused before any of it was needed");
        }
    }

    @Test
    @DisplayName("the answer is a reference to the package and says how to verify it")
    void theanswerPointsAtThePackage(@TempDir Path under) {
        corpus();
        final DocumentValue.Mapping result;
        try (StagingArea room = staging(under)) {
            result = assertInstanceOf(CommandHandler.Artifact.class,
                    new DownloadContentPackageHandler(CONTRACT, rooms(room))
                            .run(argument(List.of("/content/site"), List.of(),
                                    List.of("/content/site/private")), readOnly(), context()),
                    "the package was refused").result();
        }
        final DocumentValue.Mapping artifact = (DocumentValue.Mapping) result
                .member(ArtifactDescriptor.ARGUMENT_MEMBER).orElseThrow();
        assertEquals(new DocumentValue.Text(NAME + DownloadContentPackageResult.FILE_SUFFIX),
                artifact.member(ArtifactDescriptor.SUGGESTED_FILE_NAME).orElseThrow(),
                "a reader is not told what to save the package as");
        assertEquals(new DocumentValue.Text(DownloadContentPackageResult.MEDIA_TYPE),
                artifact.member(ArtifactDescriptor.MEDIA_TYPE).orElseThrow());
        assertTrue(((DocumentValue.Whole) artifact.member(ArtifactDescriptor.BYTE_LENGTH)
                        .orElseThrow()).value() > 0,
                "the package was reported as no bytes at all");
        assertEquals(DigestValue.RENDERED_LENGTH,
                ((DocumentValue.Text) artifact.member(ArtifactDescriptor.DIGEST).orElseThrow())
                        .value().length(),
                "the answer carries no digest a reader could verify the bytes against");
    }

    @Test
    void theProducedBytesAreAReadableVaultPackage() throws IOException {
        final String manifest = "<workspaceFilter version=\"1.0\"/>";
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(
                DownloadContentPackageHandler.packageBytes(manifest)))) {
            assertEquals("META-INF/vault/filter.xml", archive.getNextEntry().getName());
            assertEquals(manifest, new String(archive.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void thePackageCarriesSelectedResourceContent() throws IOException {
        sling.create().resource("/content/package-test", Map.of("title", "hello"));
        final byte[] bytes = DownloadContentPackageHandler.packageBytes("<workspaceFilter/>",
                sling.resourceResolver(), List.of("/content/package-test"));
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals("META-INF/vault/filter.xml", archive.getNextEntry().getName());
            archive.closeEntry();
            assertEquals("META-INF/vault/properties.xml", archive.getNextEntry().getName());
            archive.closeEntry();
            assertEquals("jcr_root/content/package-test/.content.xml",
                    archive.getNextEntry().getName(),
                    "content is not under the root FileVault reads it from");
            final String document = new String(archive.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(document.contains("title=\"hello\""), document);
            assertTrue(document.contains("xmlns:jcr=\"http://www.jcp.org/jcr/1.0\""), document);
            assertTrue(document.contains("jcr:primaryType=\""), document);
        }
    }

    @Test
    void identicalPackageInputsProduceIdenticalBytes() throws IOException {
        final String manifest = "<workspaceFilter/>";
        assertArrayEquals(DownloadContentPackageHandler.packageBytes(manifest),
                DownloadContentPackageHandler.packageBytes(manifest),
                "the archive timestamp made identical package inputs produce different bytes");
    }

    @Test
    void manifestEscapesXmlAttributeValues() {
        final DownloadContentPackageCommand command = new DownloadContentPackageCommand(
                "safe", List.of("/content/a&b", "/content/empty"), List.of(), List.of());
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<workspaceFilter version=\"1.0\"><filter root=\"/content/a&amp;b\">"
                + "<include pattern=\"\\A\\Q/content/a&amp;b/&quot;x\\E\\\\E\\Q&lt;y&gt;\\E\\z\"/>"
                + "</filter><filter root=\"/content/empty\"><include pattern=\"\\A(?!)\\z\"/>"
                + "</filter></workspaceFilter>",
                DownloadContentPackageHandler.manifestOf(command,
                        List.of("/content/a&b/\"x\\E<y>")),
                "the filter does not name exactly the selected paths, quoted and escaped");
    }

    @Test
    @DisplayName("a selection expression matches whole paths, one segment or any segments per token")
    void aselectionMatchesWholePaths() {
        final PackageSelection one = PackageSelection.of("/content/*/jcr:content").orElseThrow();
        assertTrue(one.matches("/content/site/jcr:content"));
        assertFalse(one.matches("/content/jcr:content"), "* matched no segment at all");
        assertFalse(one.matches("/content/a/b/jcr:content"), "* matched two segments");
        final PackageSelection any = PackageSelection.of("/content/site/(.*)").orElseThrow();
        assertTrue(any.matches("/content/site"), "(.*) did not match zero segments");
        assertTrue(any.matches("/content/site/a/b"));
        assertFalse(any.matches("/content/sites"), "a literal matched a longer segment");
        final PackageSelection literal = PackageSelection.of("/content/a.b").orElseThrow();
        assertFalse(literal.matches("/content/axb"), "a dot was read as syntax");
        assertTrue(literal.anchors("/content/a.b/child"), "an anchor did not select its subtree");
        assertFalse(literal.anchors("/content"), "a path above the anchor was selected");
        assertTrue(PackageSelection.of("/").orElseThrow().matches("/"));
        final PackageSelection deep = PackageSelection.of("/content/*/en/(.*)").orElseThrow();
        assertTrue(deep.reachesBelow("/content"));
        assertTrue(deep.reachesBelow("/content/site"));
        assertTrue(deep.reachesBelow("/content/site/en/page"));
        assertFalse(deep.reachesBelow("/content/site/de"), "a walk would search a dead branch");
        assertFalse(deep.reachesBelow("/apps"));
        for (final String refused : List.of("", "content", "/content//x", "/content/a*",
                "/content/")) {
            assertTrue(PackageSelection.of(refused).isEmpty(), refused + " was accepted");
        }
    }

    @Test
    @DisplayName("an inclusion below a root selects its anchor and subtree, and nothing beside it")
    void aninclusionBelowARootSelectsItsSubtree() {
        final DownloadContentPackageCommand command = assertInstanceOf(
                DownloadContentPackageCommand.Held.class,
                DownloadContentPackageCommand.of(argument(List.of("/content/site"),
                        List.of("/content/site/en"), List.of("/content/site/en/private")),
                        CONTRACT), "the filter was refused").command();
        assertFalse(command.contains("/content/site"), "the root was selected though not included");
        assertTrue(command.contains("/content/site/en"));
        assertTrue(command.contains("/content/site/en/page"));
        assertFalse(command.contains("/content/site/de"));
        assertFalse(command.contains("/content/site/en/private/deep"));
    }

    @Test
    @DisplayName("a node is written as a document FileVault reads back, typed and namespaced")
    void anodeIsAReadableDocumentView() throws javax.jcr.RepositoryException {
        final javax.jcr.Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(javax.jcr.Session.class), "a JCR session");
        final javax.jcr.Node node = session.getRootNode().addNode("docview", "nt:unstructured");
        node.setProperty("flag", true);
        node.setProperty("count", 3L);
        node.setProperty("tags", new String[] {"a,b", "c"});
        node.setProperty("brace", "{not a type}");
        node.setProperty("data", session.getValueFactory().createBinary(
                new ByteArrayInputStream(new byte[] {1, 2})));
        session.save();
        final DocumentView.Written written = DocumentView.of(node);
        final String document = written.document();
        assertTrue(document.contains("flag=\"{Boolean}true\""), document);
        assertTrue(document.contains("count=\"{Long}3\""), document);
        assertTrue(document.contains("tags=\"[a\\,b,c]\""), document);
        assertTrue(document.contains("brace=\"\\{not a type}\""), document);
        assertTrue(document.contains("data=\"{Binary}\""), document);
        assertTrue(document.contains("jcr:primaryType=\"nt:unstructured\""), document);
        assertTrue(document.contains("xmlns:nt="), "a namespace a value uses was not declared");
        assertEquals(List.of("data.binary"), List.copyOf(written.binaries().keySet()));
        assertEquals("a_x0020_b", DocumentView.encodedName("a b"));
        assertEquals("_x0031_x", DocumentView.encodedName("1x"));
    }

    @Test
    @DisplayName("a repository name is written as the file name FileVault reads it back from")
    void anameIsWrittenAsFileVaultWritesIt() {
        assertEquals("_jcr_content", PlatformNames.of("jcr:content"));
        assertEquals("__private_x", PlatformNames.of("_private_x"));
        assertEquals("a%3fb", PlatformNames.of("a?b"));
        assertEquals("/content/site/_jcr_content", PlatformNames.pathOf("/content/site/jcr:content"));
    }

    @Test
    @DisplayName("an exclusion keeps its subtree out of an included root")
    void anexclusionCarvesOutOfARoot() {
        final DownloadContentPackageCommand command = assertInstanceOf(
                DownloadContentPackageCommand.Held.class,
                DownloadContentPackageCommand.of(argument(List.of("/content/site"), List.of(),
                        List.of("/content/site/private")), CONTRACT),
                "the filter was refused").command();
        assertTrue(command.contains("/content/site/public"),
                "a path under an included root is not in the package");
        assertTrue(!command.contains("/content/site/private/secret"),
                "a path under an excluded root inside an included one is in the package, so an"
                        + " exclusion does not mean what a caller expects");
        assertTrue(!command.contains("/content/elsewhere"),
                "a path under no root at all is in the package, and the filter is the whole"
                        + " statement of what the package contains");
    }

    @Test
    @DisplayName("a package with no roots, no name, or a pattern that will not compile is refused")
    void amalformedRequestIsRefused() {
        assertEquals(DownloadContentPackageCommand.Refusal.NO_ROOTS,
                refusalOf(argument(List.of())).refusal(),
                "a package of nothing at all was accepted");
        assertEquals(DownloadContentPackageCommand.Refusal.NAME_REJECTED,
                refusalOf(named("site export")).refusal(),
                "a name a reader cannot save the package under was accepted");
        // Compiled while the argument is read rather than while the package is built. Finding out
        // that a filter is malformed after a subtree has been walked is finding out too late, and
        // the caller's answer would be a build failure rather than the malformed question it is.
        final DownloadContentPackageCommand.Refused malformed = refusalOf(
                argument(List.of("/content/site"), List.of("[unclosed"), List.of()));
        assertEquals(DownloadContentPackageCommand.Refusal.FILTER_NOT_A_PATTERN,
                malformed.refusal());
        assertEquals(DownloadContentPackageHandler.PATTERN_REJECTED,
                DownloadContentPackageHandler.categoryFor(malformed.refusal()),
                "a malformed pattern reaches a category this command's own row does not declare");
        assertEquals(java.util.Optional.of(java.util.Map.of(
                        "failure", new DocumentValue.Text("pattern_rejected"),
                        "collection", new DocumentValue.Text("exclusion"),
                        "expression_index", new DocumentValue.Whole(1))),
                DownloadContentPackageHandler.rejectedPattern(argument(List.of("/content/site"),
                        List.of("/content"), List.of("/private", "(?P<named>x)")))
                        .map(DocumentValue.Mapping::members),
                "the refusal did not name the one pattern this platform will not compile");
        assertEquals(java.util.Optional.of(new DocumentValue.Whole(0)),
                DownloadContentPackageHandler.rejectedPattern(argument(List.of("/content/site"),
                        List.of("/content"), List.of()))
                        .flatMap(document -> document.member("expression_index")),
                "a refusal for another reason did not name the first filter there is");
        assertEquals(java.util.Optional.empty(), DownloadContentPackageHandler.rejectedPattern(
                argument(List.of("/content/site"), List.of(), List.of())),
                "a request with no filter at all was given a filter to blame");
        assertTrue(DownloadContentPackageHandler.declaredCategories().containsAll(
                        java.util.Arrays.stream(DownloadContentPackageCommand.Refusal.values())
                                .map(DownloadContentPackageHandler::categoryFor)
                                .toList()),
                "an argument refusal reaches a category outside the declared set");
    }

    @Test
    @DisplayName("this command is the only one in the registry that declares room to work in")
    void thisistheOnlyCommandWithStaging() {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry();
        final List<String> withRoom = registry.rows().stream()
                .filter(row -> row.stagingBytes() > 0)
                .map(RegistryRow::wireName)
                .toList();
        assertEquals(List.of(DownloadContentPackageCommand.WIRE_NAME), withRoom,
                "the framework said there would be exactly one command declaring room inside the"
                        + " agent's own tree, and the registry now says otherwise: " + withRoom);
        final RegistryRow row = registry.row(DownloadContentPackageCommand.WIRE_NAME).orElseThrow();
        assertEquals(RegistryRow.OperationKey.REQUIRED, row.operationKey(),
                "the repository can change between two identical requests, so two packages are not"
                        + " one package and the caller supplies a key");
        assertEquals(row.failureCategories().stream().sorted().toList(),
                DownloadContentPackageHandler.declaredCategories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
    }


    private void corpus() {
        sling.build().resource("/content/site").resource("public").resource("private");
    }

    private static StagingRooms rooms(StagingArea room) {
        return () -> Optional.of(room);
    }

    private StagingArea staging(Path under) {
        try {
            Files.createDirectories(under);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return StagingArea.forRow(under, row()).orElseThrow(
                () -> new AssertionError("this command's row declares no room to work in"));
    }

    private static DownloadContentPackageCommand.Refused refusalOf(
            DocumentValue.Mapping arguments) {
        return assertInstanceOf(DownloadContentPackageCommand.Refused.class,
                DownloadContentPackageCommand.of(arguments, CONTRACT),
                "the argument was accepted");
    }

    /** What every package in this suite is called, since a package with no name is not one. */
    private static final String NAME = "site-export";

    private static DocumentValue.Mapping argument(List<String> roots) {
        return argument(roots, List.of(), List.of());
    }

    private static DocumentValue.Mapping argument(List<String> roots, List<String> inclusions,
                                                  List<String> exclusions) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(DownloadContentPackageCommand.PACKAGE_NAME, new DocumentValue.Text(NAME));
        members.put(DownloadContentPackageCommand.ROOTS, sequence(roots));
        if (!inclusions.isEmpty()) {
            members.put(DownloadContentPackageCommand.INCLUSION_FILTERS, sequence(inclusions));
        }
        if (!exclusions.isEmpty()) {
            members.put(DownloadContentPackageCommand.EXCLUSION_FILTERS, sequence(exclusions));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Sequence sequence(List<String> held) {
        return new DocumentValue.Sequence(held.stream()
                .map(value -> (DocumentValue) new DocumentValue.Text(value))
                .toList());
    }

    private static DocumentValue.Mapping named(String name) {
        final SequencedMap<String, DocumentValue> members =
                new LinkedHashMap<>(argument(List.of("/content/site")).members());
        members.put(DownloadContentPackageCommand.PACKAGE_NAME, new DocumentValue.Text(name));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static CallerContext narrowContext() {
        return new CallerContext(operation(), new Budget(Budget.Kind.DISCOVERY, 1),
                Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row() {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry()
                .row(DownloadContentPackageCommand.WIRE_NAME).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
