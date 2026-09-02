// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every bound, checked against the two things that decide whether it is trustworthy: the client's
 * own committed contract, and the digest beside this repository's copy of it.
 *
 * <p>The comparison against the sibling is name by name and value by value rather than a digest
 * over the whole document, because the two files are not the same bytes — one is the client's JSON
 * and one is this side's document carrying the same bounds plus its own. What must be identical is
 * every shared bound, and a fixture differing in exactly one of them is refused naming it.</p>
 */
final class AgentContractTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/agent-contract");

    private static final Path CONTRACT = REPOSITORY.resolve("support/agent-contract.toml");

    private static final Path DIGEST = REPOSITORY.resolve("support/agent-contract.sha256");

    private static final Path DEPLOYMENTS = REPOSITORY.resolve("support/deployments.toml");

    /** How a request window is spelled in the deployment matrix. */
    private static final Pattern REQUEST_WINDOW =
            Pattern.compile("^\\s*request_window_milliseconds\\s*=\\s*(\\d+)\\s*$", Pattern.MULTILINE);

    /** How a numeric member is spelled in the client's own contract document. */
    private static final Pattern JSON_NUMBER = Pattern.compile("\"([a-z_]+)\":(\\d+)");

    @Test
    @DisplayName("the committed contract authenticates against the digest committed beside it")
    void theCommittedContractAuthenticates() {
        assertInstanceOf(AgentContract.Loaded.class, committed());
    }

    @Test
    @DisplayName("the bundle embeds the contract, and what it embeds is what this repository commits")
    void theBundleEmbedsTheCommittedContract() {
        final AgentContract embedded = loaded(AgentContract.load());
        final AgentContract onDisk = loaded(committed());
        assertEquals(values(onDisk), values(embedded));
    }

    @Test
    @DisplayName("every shared bound equals the client's own committed contract, name by name")
    void sharedBoundsAreByteEquivalentToTheSibling() {
        assertEquals(List.of(), sharedBoundDifferences(
                FIXTURES.resolve("sibling-transport-contract.json")));
    }

    @Test
    @DisplayName("a client contract differing in one shared bound is refused naming that bound")
    void oneDifferingSharedBoundIsNamed() {
        final List<String> differences =
                sharedBoundDifferences(FIXTURES.resolve("sibling-transport-contract-differing.json"));
        assertEquals(1, differences.size(), differences.toString());
        assertTrue(differences.getFirst().contains("maximum_agent_inline_result_bytes"),
                differences.toString());
    }

    @Test
    @DisplayName("every command bound equals the client's own command contract, name by name")
    void commandBoundsAreByteEquivalentToTheSibling() {
        assertEquals(List.of(), commandBoundDifferences(
                FIXTURES.resolve("sibling-command-contract.json")));
    }

    @Test
    @DisplayName("a client command contract differing in one bound is refused naming that bound")
    void oneDifferingCommandBoundIsNamed() {
        final List<String> differences =
                commandBoundDifferences(FIXTURES.resolve("sibling-command-contract-differing.json"));
        assertEquals(1, differences.size(), differences.toString());
        assertTrue(differences.getFirst().contains("maximum_result_limit"), differences.toString());
    }

    @Test
    @DisplayName("the one bound both client contracts declare is carried twice, with both values")
    void theBoundBothClientContractsDeclareIsCarriedTwice() {
        final AgentContract contract = loaded(committed());
        final long transport =
                contract.value(ContractLimit.TRANSPORT_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES);
        final long command =
                contract.value(ContractLimit.COMMAND_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES);
        assertEquals(jsonSection(FIXTURES.resolve("sibling-transport-contract.json"), "limits")
                        .get("maximum_sling_job_identifier_bytes"),
                transport, "the transport bound is not the client's transport value");
        assertEquals(jsonSection(FIXTURES.resolve("sibling-command-contract.json"), "limits")
                        .get("maximum_sling_job_identifier_bytes"),
                command, "the command bound is not the client's command value");
        assertTrue(transport != command,
                "the two are equal here, so nothing would notice if one of them drifted");
    }

    @Test
    @DisplayName("no bound this side calls its own is one either client contract declares")
    void thisSidesOwnBoundsAreItsOwn() {
        final List<String> claimed = List.of(ContractLimit.values()).stream()
                .filter(limit -> limit.section() == ContractLimit.Section.AGENT)
                .map(ContractLimit::key)
                .filter(key -> jsonSection(FIXTURES.resolve("sibling-command-contract.json"),
                                "limits").containsKey(key)
                        || jsonSection(FIXTURES.resolve("sibling-transport-contract.json"),
                                "limits").containsKey(key))
                .toList();
        assertEquals(List.of(), claimed,
                "a bound the client declares is carried here as this side's own, which is a second"
                        + " declaration that can drift from the client's quietly");
    }

    @Test
    @DisplayName("the bundle embeds the client's own digest for each of its two contracts")
    void theBundleEmbedsBothClientDigests() {
        assertEquals(DIGEST_CHARACTERS, AgentContract.transportContractDigest().length());
        assertEquals(DIGEST_CHARACTERS, AgentContract.commandContractLimitsDigest().length());
        assertTrue(!AgentContract.transportContractDigest()
                        .equals(AgentContract.commandContractLimitsDigest()),
                "one digest is embedded twice, so one of the two contracts is unauthenticated");
    }

    /** How many characters a digest written in hexadecimal has. */
    private static final int DIGEST_CHARACTERS = 64;

    @Test
    @DisplayName("the client's own contract declares no shared bound twice with two values")
    void theClientDeclaresNoBoundTwiceWithTwoValues() {
        final SequencedMap<String, Long> limits =
                jsonSection(FIXTURES.resolve("sibling-transport-contract.json"), "limits");
        final SequencedMap<String, Long> formulas =
                jsonSection(FIXTURES.resolve("sibling-transport-contract.json"), "formulas");
        limits.forEach((name, value) ->
                assertTrue(!formulas.containsKey(name) || formulas.get(name).equals(value),
                        name + " is declared twice with two values"));
    }

    @Test
    @DisplayName("a digest that does not match refuses the load before a bound is read")
    void aDigestMismatchRefusesBeforeParsing() {
        final AgentContract.Refused refused = refusal(AgentContract.load(read(CONTRACT),
                "0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(AgentContract.Failure.DIGEST_MISMATCH, refused.failure());
        assertTrue(refused.detail().contains(AgentContract.digestOf(read(CONTRACT))),
                refused.detail());
    }

    @Test
    @DisplayName("a missing bound, an unknown bound, and an out-of-type value are refused distinctly")
    void theThreeParseFailuresAreDistinct() {
        assertEquals(AgentContract.Failure.MISSING_BOUND, refusalOf("missing-bound.toml").failure());
        assertEquals(AgentContract.Failure.UNKNOWN_BOUND, refusalOf("unknown-bound.toml").failure());
        assertEquals(AgentContract.Failure.WRONG_TYPE, refusalOf("out-of-type.toml").failure());
        assertEquals(AgentContract.Failure.UNPARSABLE, refusalOf("not-a-document.toml").failure());
    }

    @Test
    @DisplayName("every refusal names what was refused and produces no contract")
    void everyRefusalNamesWhatItRefused() {
        assertTrue(refusalOf("missing-bound.toml").detail().contains("maximum_route_query_bytes"));
        assertTrue(refusalOf("unknown-bound.toml").detail()
                .contains("maximum_nobody_declared_this_bytes"));
        assertTrue(refusalOf("out-of-type.toml").detail().contains("maximum_route_query_bytes"));
    }

    @Test
    @DisplayName("the bound set and the constant set are equal, so neither can grow alone")
    void everyDeclaredBoundHasExactlyOneConstant() {
        final AgentContract contract = loaded(committed());
        assertEquals(List.of(ContractLimit.values()).size(), contract.limits().size());
        final List<String> declaredPaths = declaredPaths();
        assertEquals(declaredPaths,
                List.of(ContractLimit.values()).stream().map(ContractLimit::path).sorted().toList());
    }

    @Test
    @DisplayName("every accessor answers the value the document declares")
    void everyAccessorAnswersTheDeclaredValue() {
        final AgentContract contract = loaded(committed());
        final SequencedMap<String, Long> declared = declaredValues();
        List.of(ContractLimit.values()).forEach(limit ->
                assertEquals(declared.get(limit.path()), contract.value(limit), limit.path()));
    }

    @Test
    @DisplayName("a session that ends on schedule is always resumable within the client's own policy")
    void theSessionBoundIsResumable() {
        final AgentContract contract = loaded(committed());
        final long session = contract.value(ContractLimit.MAXIMUM_EVENT_STREAM_SESSION_MILLISECONDS);
        final long resumable = contract.value(ContractLimit.HEARTBEAT_TIMEOUT_MILLISECONDS)
                * contract.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS);
        assertTrue(session < resumable, session + " is not below " + resumable);
        final AgentContract.Refused refused = refusalOf("session-not-resumable.toml");
        assertEquals(AgentContract.Failure.INCONSISTENT_BOUND, refused.failure());
        assertTrue(refused.detail().contains("360000"), refused.detail());
    }

    @Test
    @DisplayName("every per-caller bound is at or below the total it is a share of")
    void everyPerCallerBoundIsAtOrBelowItsTotal() {
        final AgentContract contract = loaded(committed());
        final List<String> shares = List.of(ContractLimit.values()).stream()
                .map(ContractLimit::key)
                .filter(key -> key.startsWith("maximum_caller_"))
                .toList();
        assertTrue(!shares.isEmpty(), "the contract declares no per-caller share at all");
        shares.forEach(share -> {
            final ContractLimit callerBound = soleLimitNamed(share);
            final ContractLimit total =
                    soleLimitNamed("maximum_" + share.substring("maximum_caller_".length()));
            assertTrue(contract.value(callerBound) <= contract.value(total),
                    share + " is above its own total");
        });
        final AgentContract.Refused refused = refusalOf("caller-share-above-total.toml");
        assertEquals(AgentContract.Failure.INCONSISTENT_BOUND, refused.failure());
        assertTrue(refused.detail().contains("maximum_caller_concurrent_event_streams"),
                refused.detail());
        assertTrue(refused.detail().contains("maximum_concurrent_event_streams"), refused.detail());
    }

    @Test
    @DisplayName("a command that runs to its budget still answers inside every declared window")
    void theExecutionBudgetIsBelowEveryRequestWindow() {
        final long budget =
                loaded(committed()).value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS);
        final long smallest = smallestRequestWindow(DEPLOYMENTS);
        assertTrue(budget < smallest, "an execution budget of " + budget
                + " milliseconds is not below the smallest declared request window of " + smallest);
        final long narrowed = smallestRequestWindow(FIXTURES.resolve("deployments-window-below-budget.toml"));
        assertTrue(budget >= narrowed, "the fixture matrix does not narrow the window below the budget");
    }

    // --- reading the two documents ----------------------------------------------------------

    private static List<String> sharedBoundDifferences(Path clientContract) {
        final SequencedMap<String, Long> shared = new LinkedHashMap<>();
        shared.putAll(jsonSection(clientContract, "limits"));
        jsonSection(clientContract, "formulas").forEach(shared::putIfAbsent);
        return differencesAgainst(shared, ContractLimit.Section.TRANSPORT);
    }

    private static List<String> commandBoundDifferences(Path clientContract) {
        return differencesAgainst(jsonSection(clientContract, "limits"),
                ContractLimit.Section.COMMAND);
    }

    private static List<String> differencesAgainst(SequencedMap<String, Long> shared,
                                                   ContractLimit.Section section) {
        final AgentContract contract = loaded(committed());
        final List<String> differences = new ArrayList<>();
        shared.forEach((name, value) -> {
            if (!anyBoundNamed(name)) {
                differences.add(name + " is declared by the client and carried by no bound here");
                return;
            }
            final ContractLimit limit = limitNamed(section, name);
            if (contract.value(limit) != value) {
                differences.add(name + " is " + contract.value(limit) + " here and " + value
                        + " in the client's own contract");
            }
        });
        List.of(ContractLimit.values()).stream()
                .filter(limit -> limit.section() == section)
                .map(ContractLimit::key)
                .filter(key -> !shared.containsKey(key))
                .map(key -> key + " is carried as a shared bound and the client declares no such bound")
                .forEach(differences::add);
        return List.copyOf(differences);
    }

    private static SequencedMap<String, Long> jsonSection(Path document, String section) {
        final String text = new String(read(document), StandardCharsets.UTF_8);
        final int start = text.indexOf("\"" + section + "\":{");
        assertTrue(start >= 0, document + " declares no " + section);
        final int open = text.indexOf('{', start);
        final int close = text.indexOf('}', open);
        final Matcher members = JSON_NUMBER.matcher(text.substring(open, close));
        final SequencedMap<String, Long> values = new LinkedHashMap<>();
        while (members.find()) {
            values.put(members.group(1), Long.parseLong(members.group(2)));
        }
        assertTrue(!values.isEmpty(), document + " declares an empty " + section);
        return values;
    }

    private static List<String> declaredPaths() {
        return List.copyOf(declaredValues().keySet()).stream().sorted().toList();
    }

    private static SequencedMap<String, Long> declaredValues() {
        final SequencedMap<String, Long> declared = new LinkedHashMap<>();
        final String[] table = {""};
        new String(read(CONTRACT), StandardCharsets.UTF_8).lines()
                .map(line -> line.indexOf('#') < 0 ? line : line.substring(0, line.indexOf('#')))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .forEach(line -> {
                    if (line.startsWith("[")) {
                        table[0] = line.substring(1, line.length() - 1).strip();
                        return;
                    }
                    final int assignment = line.indexOf('=');
                    declared.put(table[0] + "." + line.substring(0, assignment).strip(),
                            Long.parseLong(line.substring(assignment + 1).strip()));
                });
        return declared;
    }

    private static long smallestRequestWindow(Path matrix) {
        final Matcher windows = REQUEST_WINDOW.matcher(new String(read(matrix), StandardCharsets.UTF_8));
        long smallest = Long.MAX_VALUE;
        while (windows.find()) {
            smallest = Math.min(smallest, Long.parseLong(windows.group(1)));
        }
        assertTrue(smallest < Long.MAX_VALUE, matrix + " declares no request window");
        return smallest;
    }

    private static ContractLimit limitNamed(ContractLimit.Section section, String key) {
        return List.of(ContractLimit.values()).stream()
                .filter(limit -> limit.section() == section && limit.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + section.table() + " bound is named " + key));
    }

    private static ContractLimit soleLimitNamed(String key) {
        final List<ContractLimit> named = List.of(ContractLimit.values()).stream()
                .filter(limit -> limit.key().equals(key))
                .toList();
        assertEquals(1, named.size(), key + " does not name exactly one bound: " + named);
        return named.getFirst();
    }

    private static boolean anyBoundNamed(String key) {
        return List.of(ContractLimit.values()).stream().anyMatch(limit -> limit.key().equals(key));
    }

    private static Map<ContractLimit, Long> values(AgentContract contract) {
        final Map<ContractLimit, Long> values = new LinkedHashMap<>();
        contract.limits().forEach(limit -> values.put(limit, contract.value(limit)));
        return values;
    }

    private static AgentContract.Outcome committed() {
        return AgentContract.load(read(CONTRACT), new String(read(DIGEST), StandardCharsets.UTF_8));
    }

    private static AgentContract.Refused refusalOf(String fixture) {
        final Path document = FIXTURES.resolve(fixture);
        return refusal(AgentContract.load(read(document), AgentContract.digestOf(read(document))));
    }

    private static AgentContract loaded(AgentContract.Outcome outcome) {
        return assertInstanceOf(AgentContract.Loaded.class, outcome,
                "the contract was refused: " + outcome).contract();
    }

    private static AgentContract.Refused refusal(AgentContract.Outcome outcome) {
        return assertInstanceOf(AgentContract.Refused.class, outcome,
                "the contract was accepted where it must be refused");
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
