// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.IdentityRefusal;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A token that cannot be carried from one query to another, and everything that follows from that.
 *
 * <p>The refusal set is compared with the categories the client's own registry declares, carried
 * into this repository as a fixture: a failure that crossed the wire as a category the caller does
 * not know would be handled by reading the message beside it, which is what stable categories exist
 * to prevent.</p>
 */
final class ContinuationTokenTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/continuation-token");

    private static final AgentContract CONTRACT = contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    private static final String KEY = "a key this agent holds";

    private static final long NOW = 1787999000000L;

    @Test
    @DisplayName("a state carrying all five members is read, and each is readable")
    void aCompleteStateIsRead() {
        final ContinuationState state = held("accepted.json");
        assertEquals(1, state.generation().number());
        assertEquals(400, state.position());
        assertEquals(1788000000000L, state.expiresAtUnixMilliseconds());
        assertNotEquals(state.targetDigest().rendered(), state.queryDigest().rendered());
    }

    @Test
    @DisplayName("each of the five members absent is refused, naming that member")
    void eachAbsentMemberIsRefusedNamingIt() {
        ContinuationState.MEMBERS.forEach(member -> {
            final IdentityRefusal refusal = refusal("absent-" + member.replace('_', '-') + ".json");
            assertEquals(IdentityRefusal.Failure.MEMBER_ABSENT, refusal.failure(), member);
            assertEquals(member, refusal.member());
        });
        assertEquals(IdentityRefusal.Failure.MEMBER_UNKNOWN,
                refusal("a-sixth-member.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_A_DOCUMENT,
                assertInstanceOf(ContinuationState.Refused.class,
                        ContinuationState.of(value("not-an-object.json")),
                        "a number was read as a state").refusal().failure());
        assertEquals(IdentityRefusal.Failure.OUT_OF_RANGE,
                refusal("position-below-zero.json").failure());
        assertEquals(IdentityRefusal.Failure.OUT_OF_RANGE,
                refusal("generation-zero.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_A_DIGEST,
                refusal("digest-that-is-not-one.json").failure());
    }

    @Test
    @DisplayName("a token from another query and one from another target are refused distinctly")
    void aTokenCannotBeCarriedFromOneQueryToAnother() {
        final ContinuationState state = held("accepted.json");
        final ContinuationToken token = ContinuationToken.issue(state, KEY);
        assertInstanceOf(ContinuationToken.Honoured.class, honour(token, state), "a token this"
                + " agent issued for this query was not honoured");
        assertEquals(ContinuationToken.Refusal.WRONG_QUERY,
                refusedBy(token, held("another-query.json")));
        assertEquals(ContinuationToken.Refusal.WRONG_TARGET,
                refusedBy(token, held("another-target.json")));
        // A store that was rebuilt is serving a later incarnation, and the token in hand was
        // issued against the one before it.
        assertEquals(ContinuationToken.Refusal.WRONG_GENERATION,
                assertInstanceOf(ContinuationToken.Refused.class,
                        token.validate(ring(), state.targetDigest(), theQuery(), rebuilt(), NOW,
                                CONTRACT),
                        "a token from before a rebuild was honoured after it").refusal());
    }

    @Test
    @DisplayName("a tampered token is refused before anything it names is compared")
    void integrityComesFirst() {
        final ContinuationState state = held("accepted.json");
        final ContinuationToken forged = ContinuationToken.arrived(
                ContinuationToken.issue(held("another-query.json"), KEY).integrity(), state);
        assertEquals(ContinuationToken.Refusal.INTEGRITY_INVALID, refusedBy(forged, state),
                "a forged token reached a comparison against the data it named");
    }

    @Test
    @DisplayName("an expired token is refused, and only after everything it names has matched")
    void anExpiredTokenIsRefusedLast() {
        final ContinuationState state = held("accepted.json");
        final ContinuationToken token = ContinuationToken.issue(state, KEY);
        assertEquals(ContinuationToken.Refusal.EXPIRED,
                assertInstanceOf(ContinuationToken.Refused.class,
                        token.validate(ring(), state.targetDigest(), theQuery(), serving(),
                                state.expiresAtUnixMilliseconds(), CONTRACT)).refusal());
    }

    @Test
    @DisplayName("this build refuses a token for exactly the categories the client declares")
    void therefusalSetIsTheClientsOwn() {
        final List<String> declared = assertInstanceOf(DocumentValue.Sequence.class,
                document("client-refusal-categories.json").member("category").orElseThrow())
                .items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Text.class, item).value())
                .sorted()
                .toList();
        assertEquals(declared, Arrays.stream(ContinuationToken.Refusal.values())
                        .map(refusal -> refusal.name().toLowerCase(Locale.ROOT))
                        .sorted()
                        .toList(),
                "this build and the client disagree about why a token is refused");
    }

    @Test
    @DisplayName("a query whose arguments cannot be written canonically has no digest")
    void aQueryThatCannotBeWrittenHasNoDigest() {
        final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>();
        arguments.put("root_path", new DocumentValue.Text("\ud800"));
        final QueryDigest.Refused refused = assertInstanceOf(QueryDigest.Refused.class,
                QueryDigest.of("query_paths", new DocumentValue.Mapping(arguments)),
                "a query carrying half a character was digested");
        assertTrue(refused.detail().contains("/root_path"), refused.detail());
    }

    @Test
    @DisplayName("the state bound is the contract's own, applied with no deployment-conditional path")
    void theStateBoundIsUniversal() {
        final ContinuationToken token = ContinuationToken.issue(held("accepted.json"), KEY);
        final long bound =
                CONTRACT.value(ContractLimit.MAXIMUM_AGENT_CONTINUATION_KEY_STATE_BYTES);
        assertTrue(token.stateBytes() <= bound,
                token.stateBytes() + " bytes is past the bound of " + bound);
        final String source = sourceOf("ContinuationToken.java") + sourceOf("KeyRing.java")
                + sourceOf("ContinuationKeyAuthority.java");
        List.of("nodeCount", "isSingleInstance", "cluster", "standalone", "deployment ==")
                .forEach(branch -> assertFalse(source.contains(branch),
                        "a path here branches on " + branch));
    }

    @Test
    @DisplayName("the query digest moves when any one argument does, one argument at a time")
    void theQueryDigestIsSensitiveToEveryArgument() {
        final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>();
        arguments.put("root_path", new DocumentValue.Text("/content"));
        arguments.put("limit", new DocumentValue.Whole(10));
        arguments.put("offset", new DocumentValue.Whole(0));
        arguments.put("descending", new DocumentValue.Flag(DocumentValue.Truth.FALSE));
        final String base = digestOf("query_paths", arguments);
        assertEquals(base, digestOf("query_paths", arguments), "the same query moved");
        assertEquals(theQuery(), theQuery(), "two derivations of one query are two values");
        assertEquals(theQuery().hashCode(), theQuery().hashCode());
        assertEquals(theQuery().value().rendered(), theQuery().toString());
        assertNotEquals(base, digestOf("query_page_paths", arguments),
                "the command's own name is not part of what a token belongs to");
        arguments.keySet().forEach(argument -> {
            final SequencedMap<String, DocumentValue> changed = new LinkedHashMap<>(arguments);
            changed.put(argument, moved(arguments.get(argument)));
            assertNotEquals(base, digestOf("query_paths", changed),
                    "changing " + argument + " did not change which query this is");
        });
    }

    private static DocumentValue moved(DocumentValue value) {
        return switch (value) {
            case DocumentValue.Text text -> new DocumentValue.Text(text.value() + "/en");
            case DocumentValue.Whole whole -> new DocumentValue.Whole(whole.value() + 1);
            case DocumentValue.Flag flag -> new DocumentValue.Flag(
                    flag.value() == DocumentValue.Truth.TRUE
                            ? DocumentValue.Truth.FALSE
                            : DocumentValue.Truth.TRUE);
            default -> new DocumentValue.Nothing();
        };
    }

    private static String digestOf(String command, SequencedMap<String, DocumentValue> arguments) {
        return assertInstanceOf(QueryDigest.Held.class,
                QueryDigest.of(command, new DocumentValue.Mapping(arguments)),
                "the arguments could not be written canonically").digest().value().rendered();
    }

    private static ContinuationToken.Outcome honour(ContinuationToken token,
                                                    ContinuationState expected) {
        return token.validate(ring(), expected.targetDigest(), queryOf(expected), serving(), NOW,
                CONTRACT);
    }

    /** The query one state belongs to: the one these fixtures name, or the other one. */
    private static QueryDigest queryOf(ContinuationState expected) {
        return expected.queryDigest().matches(theQuery().value())
                ? theQuery()
                : query("/content/en");
    }

    private static ContinuationToken.Refusal refusedBy(ContinuationToken token,
                                                       ContinuationState expected) {
        return assertInstanceOf(ContinuationToken.Refused.class, honour(token, expected),
                "a token was honoured that should not have been").refusal();
    }

    /**
     * The query these fixtures belong to, derived rather than repeated: the digest in the accepted
     * state is the one this derivation produces, so the two cannot drift apart.
     */
    private static QueryDigest query(String rootPath) {
        final DocumentValue.Mapping declared = document("the-query.json");
        final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>(
                assertInstanceOf(DocumentValue.Mapping.class,
                        declared.member("arguments").orElseThrow()).members());
        arguments.put("root_path", new DocumentValue.Text(rootPath));
        return assertInstanceOf(QueryDigest.Held.class,
                QueryDigest.of(assertInstanceOf(DocumentValue.Text.class,
                                declared.member("command_wire_name").orElseThrow()).value(),
                        new DocumentValue.Mapping(arguments)),
                "the query digest was refused").digest();
    }

    private static QueryDigest theQuery() {
        return query("/content");
    }

    private static KeyRing ring() {
        return KeyRing.initial(KEY);
    }

    private static EventStoreGeneration rebuilt() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST + 1),
                "a later generation is not one").generation();
    }

    private static EventStoreGeneration serving() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation is not one").generation();
    }

    private static ContinuationState held(String fixture) {
        return assertInstanceOf(ContinuationState.Held.class,
                ContinuationState.of(document(fixture)), fixture + " was refused").state();
    }

    private static IdentityRefusal refusal(String fixture) {
        return assertInstanceOf(ContinuationState.Refused.class,
                ContinuationState.of(document(fixture)),
                fixture + " was read as a state").refusal();
    }

    private static DocumentValue.Mapping document(String fixture) {
        return assertInstanceOf(DocumentValue.Mapping.class, value(fixture));
    }

    private static DocumentValue value(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(read(FIXTURES.resolve(fixture)), DOCUMENT_BOUNDS),
                "the fixture is not a document this reader accepts").value();
    }

    private static String sourceOf(String name) {
        return new String(read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/continuation").resolve(name)),
                StandardCharsets.UTF_8);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
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
