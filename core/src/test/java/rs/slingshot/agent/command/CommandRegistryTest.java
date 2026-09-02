// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.CommandContractIdentity;

/**
 * One file per command, and every way a file can fail to be one.
 *
 * <p>The ordering test is the one worth reading. Files are found in whatever order a directory
 * hands them over, and a registry whose order followed that would be a discovery document that
 * differed between two machines running the same commit — so the rows come back in wire order and
 * the fixture directory is deliberately named out of it.</p>
 */
final class CommandRegistryTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/command-registry");

    private static final AgentContract CONTRACT = contract();

    @Test
    @DisplayName("a well-formed directory loads, in wire order however the files were found")
    void awellFormedDirectoryLoadsInWireOrder() {
        final CommandRegistry registry = loaded("accepted");
        assertEquals(List.of("download_content_package", "list_child_pages", "query_paths"),
                registry.wireNames(),
                "the rows are not in wire order, so two builds would enumerate differently");
        assertEquals(3, registry.rows().size());
        assertTrue(registry.row("query_paths").isPresent());
        assertTrue(registry.row("a_command_nobody_declared").isEmpty());
    }

    @Test
    @DisplayName("every member a row has is required, and each absence is refused naming it")
    void everymemberIsRequired() {
        for (final String fixture : List.of("missing-wire-name", "missing-contract-version",
                "missing-access", "missing-operation-key", "missing-result-bound",
                "missing-failure-categories", "missing-argument-digest", "missing-result-digest",
                "missing-limits-digest", "missing-staging-budget", "missing-execution")) {
            final CommandRegistry.Refused refused = refusal(fixture);
            assertEquals(CommandRegistry.Failure.MEMBER_ABSENT, refused.failure(),
                    fixture + " was refused for something other than the member it is missing: "
                            + refused.detail());
            assertTrue(refused.detail().contains("row.toml"), refused.detail());
        }
    }

    @Test
    @DisplayName("two files declaring one wire name are refused naming it")
    void twofilesDeclaringOneWireNameAreRefused() {
        final CommandRegistry.Refused refused = refusal("duplicate-wire-name");
        assertEquals(CommandRegistry.Failure.DUPLICATE_WIRE_NAME, refused.failure());
        assertTrue(refused.detail().contains("query_paths"), refused.detail());
    }

    @Test
    @DisplayName("a read that requires an operation key loads, because reading is not idempotency")
    void areadMayRequireAnOperationKey() {
        assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES.resolve("read-requiring-a-key")),
                "a read that requires an operation key was refused, on the reasoning that a read"
                        + " is intrinsically idempotent - which is not true and is not what the"
                        + " client says: reading a repository twice is not one operation when the"
                        + " repository can change in between, and the client publishes two such"
                        + " reads beside twenty-six that refuse a key");
    }

    @Test
    @DisplayName("a staging budget is what decides whether a command has room, not its name")
    void astagingBudgetIsWhatDecidesRoom() {
        final CommandRegistry registry = loaded("accepted");
        assertEquals(RegistryRow.Staging.INSIDE_THE_AGENTS_OWN_TREE,
                registry.row("download_content_package").orElseThrow().staging(),
                "a row declaring room does not get any");
        assertEquals(RegistryRow.Staging.NONE_AT_ALL,
                registry.row("query_paths").orElseThrow().staging(),
                "a row declaring no room got some anyway");
        assertEquals(0, registry.row("query_paths").orElseThrow().stagingBytes());
    }

    @Test
    @DisplayName("a deferred row is refused naming the identity question it has not answered")
    void adeferredRowIsRefused() {
        final CommandRegistry.Refused refused = refusal("deferred-row");
        assertEquals(CommandRegistry.Failure.NO_IDENTITY_ANSWER, refused.failure());
        assertTrue(refused.detail().contains("identity"), refused.detail());
        loaded("accepted").rows().forEach(row -> assertEquals(ExecutionClass.IMMEDIATE,
                row.executionClass(), row.wireName() + " runs somewhere else"));
    }

    @Test
    @DisplayName("a class this build does not know, and a bound of nothing, are refused apart")
    void anunknownClassAndAnEmptyBoundAreRefusedApart() {
        assertEquals(CommandRegistry.Failure.MEMBER_UNKNOWN, refusal("unknown-access").failure());
        assertEquals(CommandRegistry.Failure.UNPARSABLE, refusal("zero-result-bound").failure());
    }

    @Test
    @DisplayName("every row derives its own identity from its own members and nothing else")
    void everyrowDerivesItsOwnIdentity() {
        loaded("accepted").rows().forEach(row -> {
            final CommandContractIdentity identity = assertInstanceOf(
                    CommandContractIdentity.Held.class,
                    row.identity(CommandContractIdentity.Bounds.from(CONTRACT)),
                    row.wireName() + " has no identity").identity();
            assertEquals(row.wireName(), identity.wireName());
            assertEquals(row.contractVersion(), identity.contractVersion());
            assertEquals(row.argumentDigest(), identity.argumentSchemaDigest().rendered());
            assertEquals(row.resultDigest(), identity.resultSchemaDigest().rendered());
        });
    }

    @Test
    @DisplayName("a row whose digest is not a digest has no identity rather than a wrong one")
    void arowWhoseDigestIsNotAdigestHasNone() {
        final RegistryRow row = new RegistryRow("query_paths", "1", AccessClass.READ,
                RegistryRow.OperationKey.REFUSED, 1024, List.of("not_found"),
                "not-a-digest", "not-a-digest", "not-a-digest", 0, ExecutionClass.IMMEDIATE);
        assertInstanceOf(CommandContractIdentity.Refused.class,
                row.identity(CommandContractIdentity.Bounds.from(CONTRACT)),
                "a row whose digests are not digests was given an identity");
    }

    @Test
    @DisplayName("an empty directory is an empty registry, and a missing one is refused")
    void anemptyDirectoryIsAnEmptyRegistry() {
        assertEquals(List.of(), loaded("empty").wireNames(),
                "an empty directory did not produce an empty registry");
        assertEquals(CommandRegistry.Failure.UNREADABLE,
                CommandRegistry.refusalIn(CommandRegistry.read(FIXTURES.resolve("nothing-here")))
                        .orElseThrow().failure(),
                "a directory nobody made was read as an empty one");
    }

    @Test
    @DisplayName("the committed registry loads, and every row it holds runs inside its request")
    void thecommittedRegistryLoads() {
        final CommandRegistry committed = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve(CommandRegistry.REGISTRY_DIRECTORY)),
                "the committed registry was refused").registry();
        committed.rows().forEach(row -> {
            assertEquals(ExecutionClass.IMMEDIATE, row.executionClass(),
                    row.wireName() + " runs somewhere this build has no identity answer for");
            assertTrue(row.disagreement().isEmpty(), row.wireName() + " disagrees with itself");
        });
    }

    @Test
    @DisplayName("two registries collected together hold every row, still in wire order")
    void tworegistriesCollectedTogetherStayInWireOrder() {
        final CommandRegistry both = CommandRegistry.of(List.of(loaded("accepted"),
                loaded("empty")));
        assertEquals(List.of("download_content_package", "list_child_pages", "query_paths"),
                both.wireNames());
    }

    private static CommandRegistry.Refused refusal(String fixture) {
        return CommandRegistry.refusalIn(CommandRegistry.read(FIXTURES.resolve(fixture)))
                .orElseThrow(() -> new IllegalStateException(fixture + " was accepted"));
    }

    private static CommandRegistry loaded(String fixture) {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES.resolve(fixture)),
                fixture + " was refused").registry();
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
