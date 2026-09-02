// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.jcr.Session;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import rs.slingshot.agent.contract.AgentContract;

/**
 * "This command replaces nothing" as a property of the machinery rather than a claim in a table.
 *
 * <p>A read handler that commits three frames down through a helper is the one that gets past
 * review, so the refusal is in the resolver: whatever a handler does with what it was given, and
 * however many helpers it goes through, the write does not happen.</p>
 *
 * <p>The staging test is the one that keeps the claim precise. A read command replaces nothing
 * <em>the caller owns</em>; the one command that needs scratch space writes into this agent's own
 * tree, through framework-owned code, and still cannot reach the caller's repository with anything
 * but this resolver.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ReadOnlyResolverTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/command-registry/accepted");

    private static final AgentContract CONTRACT = contract();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a commit is refused, directly and through a helper, with the same refusal")
    void acommitIsRefusedDirectlyAndThroughAhelper() {
        final PersistenceException direct = assertThrows(PersistenceException.class,
                () -> reading().commit(), "a read command committed");
        final PersistenceException throughAhelper = assertThrows(PersistenceException.class,
                () -> commitsThreeFramesDown(reading()),
                "a read command committed through a helper");
        assertEquals(direct.getMessage(), throughAhelper.getMessage(),
                "the two ways of committing are told apart, and one of them is the one that gets"
                        + " past review");
        assertTrue(direct.getMessage().contains("replaces nothing"), direct.getMessage());
    }

    private static void commitsThreeFramesDown(ResourceResolver reading)
            throws PersistenceException {
        oneFrameDown(reading);
    }

    private static void oneFrameDown(ResourceResolver reading) throws PersistenceException {
        twoFramesDown(reading);
    }

    private static void twoFramesDown(ResourceResolver reading) throws PersistenceException {
        reading.commit();
    }

    @Test
    @DisplayName("every other way of writing through it is refused too")
    void everyotherWayOfWritingIsRefused() {
        final Resource root = reading().getResource("/");
        assertNotNull(root, "a read command cannot read, which is not a read command");
        assertThrows(PersistenceException.class, () -> reading().delete(root));
        assertThrows(PersistenceException.class, () -> reading().create(root, "new", Map.of()));
        assertThrows(PersistenceException.class, () -> reading().move("/one", "/other"));
        assertThrows(PersistenceException.class, () -> reading().copy("/one", "/other"));
        assertThrows(UnsupportedOperationException.class, () -> reading().revert());
        assertThrows(UnsupportedOperationException.class, () -> reading().refresh());
        assertThrows(UnsupportedOperationException.class, () -> reading().clone(Map.of()));
        assertFalse(reading().hasChanges(), "a resolver nothing can write through has changes");
    }

    @Test
    @DisplayName("it yields no session, and everything else adapts as it always did")
    void ityieldsNosessionAndAdaptsOtherwise() {
        assertNull(reading().adaptTo(Session.class),
                "a read command adapted to a session, which is every write the caller has");
        assertNotNull(sling.resourceResolver().adaptTo(Session.class),
                "the caller's own resolver has no session either, so nothing above was proved");
        assertNotNull(reading().getResource("/"), "a read command cannot read");
        assertEquals(sling.resourceResolver().getUserID(), reading().getUserID(),
                "a read command runs as somebody other than the caller");
    }

    @Test
    @DisplayName("a handler holding a staging area is refused a commit exactly as one without is")
    void ahandlerHoldingStagingIsRefusedAcommitToo(@TempDir Path scratch) {
        try (StagingArea area = StagingArea.forRow(scratch.resolve("under-the-agents-own-tree"),
                row("download_content_package")).orElseThrow()) {
            assertInstanceOf(StagingArea.Written.class, area.write("held.txt", "anything"),
                    "the staging write this command is allowed was refused");
            assertThrows(PersistenceException.class, () -> reading().commit(),
                    "a command with somewhere to write was allowed to write to the caller's"
                            + " repository as well");
            assertTrue(Files.isDirectory(scratch.resolve("under-the-agents-own-tree")),
                    "the staging write did not reach the agent's own tree");
        }
        assertFalse(reading().hasChanges(), "something was staged in the caller's own session");
    }

    @Test
    @DisplayName("an undeclared query is refused, and a plan that would walk is refused before it runs")
    void anundeclaredQueryAndAwalkingPlanAreRefused() {
        final DeclaredQuery declared = new DeclaredQuery("list_child_pages",
                "SELECT * FROM [cq:Page] AS page WHERE ISDESCENDANTNODE(page, $root)",
                List.of("/content"), List.of("jcr:primaryType"), "list_child_pages");
        final DeclaredQuery.Refused undeclared = assertInstanceOf(DeclaredQuery.Refused.class,
                DeclaredQuery.permitted(List.of(declared), "SELECT * FROM [nt:base]",
                        "/* traverse */"),
                "a query nobody declared was run");
        assertEquals(DeclaredQuery.Refusal.UNDECLARED, undeclared.refusal());
        final DeclaredQuery.Refused walking = assertInstanceOf(DeclaredQuery.Refused.class,
                DeclaredQuery.permitted(List.of(declared), declared.statement(),
                        "no-index traverse /content"),
                "a query that would walk the repository was run");
        assertEquals(DeclaredQuery.Refusal.WOULD_TRAVERSE, walking.refusal());
        assertTrue(walking.detail().contains("list_child_pages"), walking.detail());
        assertInstanceOf(DeclaredQuery.Permitted.class,
                DeclaredQuery.permitted(List.of(declared), declared.statement(),
                        "cqPageLucene /content"),
                "a declared query answered from an index was refused");
        assertEquals(Budget.Kind.DISCOVERY.category(), DeclaredQuery.category(),
                "a query that would walk is reported as something other than running out of rows");
    }

    @Test
    @DisplayName("a query restricted to no subtree is not a query this build will hold")
    void aqueryRestrictedToNoSubtreeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeclaredQuery("everything", "SELECT * FROM [nt:base]", List.of(),
                        List.of(), "everything"),
                "a query starting at the top of somebody's repository was accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new DeclaredQuery("", "SELECT * FROM [nt:base]", List.of("/content"),
                        List.of(), "somebody"),
                "a query with no name was accepted, and a refusal has to name one");
    }

    /**
     * The resolver a read handler is given, which is the caller's own with nothing writable on it.
     *
     * <p>Made afresh at each use rather than held in a local: what it wraps is the suite's own
     * resolver, closed by the extension that opened it, and a wrapper closed here would close the
     * thing every other assertion in this file reads through.</p>
     *
     * @return the resolver
     */
    private ResourceResolver reading() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static RegistryRow row(String wireName) {
        return assertInstanceOf(CommandRegistry.Loaded.class, CommandRegistry.read(FIXTURES),
                "the fixture registry was refused").registry().row(wireName).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
