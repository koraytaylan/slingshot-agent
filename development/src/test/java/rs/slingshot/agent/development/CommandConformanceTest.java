// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command exists when six things are true, and each absence says which one.
 *
 * <p>Every case here starts from one complete command and removes exactly one fact, because a
 * fixture per case would drift from the complete one and stop proving the thing it was written for.
 * What the removal proves is that each fact is checked separately: a checker reporting one
 * "incomplete" finding for all six would pass every one of these.</p>
 */
final class CommandConformanceTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path COMPLETE = REPOSITORY.resolve(
            "development/src/test/resources/fixtures/command-conformance/complete");

    /** The command the fixture tree declares, which the client's own table also names. */
    private static final String COMMAND = "query_paths";

    @Test
    @DisplayName("a command with all six facts produces no finding at all")
    void acompleteCommandPasses(@TempDir Path root) {
        assertEquals(List.of(), CommandConformance.against(copied(COMPLETE, root)));
    }

    @Test
    @DisplayName("each of the six facts, removed on its own, fails naming that fact and the command")
    void eachMissingFactFailsDistinctly(@TempDir Path root) {
        final List<String> rules = List.of(
                without(root, "argument", "schemas/agent-protocol/command/query_paths-arguments.json"),
                without(root, "result", "schemas/agent-protocol/command/query_paths-result.json"),
                without(root, "model", "core/src/main/java/rs/slingshot/agent/command/QueryPaths.java"),
                without(root, "scenario", "interop/scenarios/query-paths.toml"));
        assertEquals(List.of(CommandConformance.Fact.ARGUMENT_SCHEMA.rule(),
                        CommandConformance.Fact.RESULT_SCHEMA.rule(),
                        CommandConformance.Fact.TYPED_MODEL.rule(),
                        CommandConformance.Fact.INTEROP_SCENARIO.rule()),
                rules, "removing one fact did not report that fact");
        assertEquals(rules.size(), rules.stream().distinct().count(),
                "two different missing facts are reported under one rule, so a reader cannot tell"
                        + " which of them to go and write");
    }

    @Test
    @DisplayName("a row that declares no failure set fails as a failure set rather than as a row")
    void amissingFailureSetIsItsOwnFact(@TempDir Path root) {
        final Path tree = copied(COMPLETE, root);
        final Path row = tree.resolve("policy/commands/query_paths.toml");
        write(row, read(row).lines()
                .filter(line -> !line.startsWith("failure_categories"))
                .collect(java.util.stream.Collectors.joining("\n")));
        assertEquals(List.of(CommandConformance.Fact.FAILURE_SET.rule()),
                rulesOf(CommandConformance.against(tree)));
    }

    @Test
    @DisplayName("a bound with no vector at it, or none past it, names which side is missing")
    void amissingVectorNamesItsSide(@TempDir Path root) {
        final Path tree = copied(COMPLETE, root);
        final Path vectors = tree.resolve("schemas/agent-protocol-vectors.json");
        write(vectors, read(vectors).replace("\"edge\":\"past\"", "\"edge\":\"neither\""));
        final List<PolicyFinding> findings = CommandConformance.against(tree);
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(CommandConformance.Fact.BOUND_VECTORS.rule(), findings.getFirst().rule());
        assertTrue(findings.getFirst().symbol()
                        .endsWith("vector " + CommandConformance.PAST_THE_BOUND),
                "the finding does not say which side of the bound has no vector: "
                        + findings.getFirst().symbol());
        assertTrue(!findings.getFirst().symbol()
                        .endsWith("vector " + CommandConformance.AT_THE_BOUND),
                "a vector that is there was reported missing");
    }

    @Test
    @DisplayName("a command here and not in the client's table, and the reverse, are two findings")
    void thetwoDivergencesAreDistinct(@TempDir Path root) {
        final Path here = copied(COMPLETE, root.resolve("here"));
        write(here.resolve(CommandConformance.CLIENT_TABLE),
                "{\"command_semantic_contract_versions\":{\"list_child_pages\":\"1.0.0\"},"
                        + "\"limits\":{}}");
        final List<String> blocking = rulesOf(CommandConformance.against(here));
        assertTrue(blocking.contains(CommandConformance.Divergence.NOT_IN_THE_CLIENT_TABLE.rule()),
                "a command this side serves and no client will ask for was not reported: "
                        + blocking);
        assertTrue(!blocking.contains(CommandConformance.Divergence.NOT_IN_THIS_REGISTRY.rule()),
                "an unwritten command failed the gate, which would leave it red from the first"
                        + " command to the sixty-fourth: " + blocking);
        final List<String> left = rulesOf(CommandConformance.unimplemented(here));
        assertEquals(List.of(CommandConformance.Divergence.NOT_IN_THIS_REGISTRY.rule()), left,
                "a command the client publishes and this side has not written was not counted");
    }

    @Test
    @DisplayName("a row whose key requirement is not the client's own is refused naming the client's")
    void arowThatDiffersFromTheClientIsRefused(@TempDir Path root) {
        final Path tree = copied(COMPLETE, root);
        final Path row = tree.resolve("policy/commands/query_paths.toml");
        write(row, read(row).replace("operation_key = \"refused\"",
                "operation_key = \"required\""));
        final List<PolicyFinding> findings = CommandConformance.against(tree);
        assertEquals(List.of(CommandConformance.Fact.CLIENT_CLASSIFICATION_AGREES.rule()),
                rulesOf(findings),
                "a row claiming a key requirement the client does not publish was accepted, which"
                        + " is the two halves disagreeing about the same command");
        assertTrue(findings.getFirst().symbol().contains("refused"),
                "the finding does not say what the client actually says: "
                        + findings.getFirst().symbol());
    }

    @Test
    @DisplayName("a command missing three facts reports all three at once")
    void everyFailureIsReportedTogether(@TempDir Path root) {
        final Path tree = copied(COMPLETE, root);
        delete(tree.resolve("schemas/agent-protocol/command/query_paths-arguments.json"));
        delete(tree.resolve("schemas/agent-protocol/command/query_paths-result.json"));
        delete(tree.resolve("interop/scenarios/query-paths.toml"));
        assertEquals(List.of(CommandConformance.Fact.ARGUMENT_SCHEMA.rule(),
                        CommandConformance.Fact.INTEROP_SCENARIO.rule(),
                        CommandConformance.Fact.RESULT_SCHEMA.rule()),
                rulesOf(CommandConformance.against(tree)),
                "somebody adding this command would have to run the gate three times to learn"
                        + " what it still needs");
    }

    @Test
    @DisplayName("the check reads the registry directory rather than any list written into it")
    void thecheckReadsTheDirectory(@TempDir Path root) {
        final Path tree = copied(COMPLETE, root);
        write(tree.resolve("policy/commands/a_command_nobody_wrote_down.toml"),
                "[command]\nwire_name = \"a_command_nobody_wrote_down\"\n"
                        + "failure_categories = [\"not_found\"]\n");
        final List<String> rules = rulesOf(CommandConformance.against(tree));
        assertTrue(rules.contains(CommandConformance.Fact.ARGUMENT_SCHEMA.rule()),
                "a row added to the directory was invisible to the check, so the check is reading"
                        + " a list rather than the registry: " + rules);
        assertTrue(rules.contains(CommandConformance.Divergence.NOT_IN_THE_CLIENT_TABLE.rule()),
                "the added row was not compared against the client's table: " + rules);
    }

    @Test
    @DisplayName("this repository's own registry is empty and conformant, which is not a failure")
    void thisRepositoryIsConformant() {
        assertEquals(List.of(), CommandConformance.registeredCommands(REPOSITORY).stream()
                        .filter(command -> command.isBlank())
                        .toList(),
                "the registry holds a command with no name");
        assertEquals(SIXTY_FOUR, CommandConformance.publishedCommands(REPOSITORY).size(),
                "the client's published table is not the size this suite was written against");
        assertEquals(SIXTY_FOUR - CommandConformance.registeredCommands(REPOSITORY).size(),
                CommandConformance.unimplemented(REPOSITORY).size(),
                "the count of commands still to write does not follow from what is written");
    }

    /** How many commands the client's own table publishes. */
    private static final int SIXTY_FOUR = 64;

    private static String without(Path root, String named, String fact) {
        final Path tree = copied(COMPLETE, root.resolve(named));
        delete(tree.resolve(fact));
        final List<PolicyFinding> findings = CommandConformance.against(tree);
        assertEquals(1, findings.size(),
                "removing " + fact + " produced " + findings.size() + " findings: " + findings);
        assertEquals(COMMAND, findings.getFirst().symbol(),
                "the finding does not name the command it is about");
        return findings.getFirst().rule();
    }

    private static List<String> rulesOf(List<PolicyFinding> findings) {
        return findings.stream().map(PolicyFinding::rule).sorted().toList();
    }

    private static Path copied(Path from, Path to) {
        try (Stream<Path> tree = Files.walk(from)) {
            tree.sorted().forEach(source -> {
                final Path target = to.resolve(from.relativize(source).toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                        return;
                    }
                    Files.createDirectories(directoryHolding(target));
                    Files.copy(source, target);
                } catch (final IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return to;
    }

    private static void delete(Path file) {
        try {
            assertTrue(Files.deleteIfExists(file), file + " was not there to remove");
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path directoryHolding(Path file) {
        // A path is allowed to have no parent, and one that does is holding itself: creating the
        // current directory is what "make sure this file's directory is there" means for it.
        final Path holding = file.getParent();
        return holding == null ? file.getFileSystem().getPath("") : holding;
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(directoryHolding(file));
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

}
