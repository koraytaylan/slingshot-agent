// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A window measured from where the client measures it, and refused rather than quietly shortened.
 *
 * <p>The anchoring test is the one that matters: a record written an hour after the request it
 * belongs to still stops being kept at the same instant it would have if it had been written
 * immediately, because the client's arithmetic is about its own request rather than about when this
 * side got round to it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class RetentionPolicyTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/retention");

    private static final AgentContract CONTRACT = contract();

    private static final long REQUEST_START = 1788000000000L;

    private static final long AN_HOUR = 3600000;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every kind is kept from its record's request-start for the contract's minimum")
    void everykindIsKeptFromTheRequestStart() throws RepositoryException {
        final Session session = recorded(REQUEST_START);
        for (final RetentionPolicy.Kind kind : RetentionPolicy.Kind.values()) {
            final RetainedUntil until = assertInstanceOf(RetentionPolicy.Held.class,
                    RetentionPolicy.until(session, operation(), kind, CONTRACT),
                    kind + " has no retention").retainedUntil();
            assertEquals(kind, until.kind());
            assertEquals(REQUEST_START + kind.minimum(CONTRACT), until.instantUnixMilliseconds(),
                    kind + " is not kept for the contract's own minimum");
            assertTrue(until.hasPassed(until.instantUnixMilliseconds()),
                    "a retention that has arrived was said not to have");
            assertFalse(until.hasPassed(until.instantUnixMilliseconds() - 1),
                    "a retention that has not arrived was said to have");
        }
        assertEquals(4, RetentionPolicy.Kind.values().length, "a retained kind was added or lost");
        assertTrue(RetentionPolicy.Kind.named("everything").isEmpty());
        assertEquals(RetentionPolicy.Kind.ARTIFACT,
                RetentionPolicy.Kind.named("artifact").orElseThrow());
    }

    @Test
    @DisplayName("a record written later than its request is kept until the same instant")
    void arecordWrittenLaterIsKeptUntilTheSameInstant() throws RepositoryException {
        final Session written = recorded(REQUEST_START);
        final RetainedUntil immediate = assertInstanceOf(RetentionPolicy.Held.class,
                RetentionPolicy.until(written, operation(), RetentionPolicy.Kind.RESULT, CONTRACT))
                .retainedUntil();
        final Node record = written.getNode(operation().path());
        record.setProperty("written_at_unix_milliseconds", REQUEST_START + AN_HOUR);
        written.save();
        assertEquals(immediate.instantUnixMilliseconds(), assertInstanceOf(
                RetentionPolicy.Held.class, RetentionPolicy.until(written, operation(),
                        RetentionPolicy.Kind.RESULT, CONTRACT)).retainedUntil()
                .instantUnixMilliseconds(),
                "a record written later is kept longer, which lengthens a window nobody agreed to");
    }

    @Test
    @DisplayName("a configured retention holds at exactly the minimum and is refused below it")
    void aconfiguredRetentionHoldsAtTheMinimum() throws RepositoryException {
        final Session session = recorded(REQUEST_START);
        for (final RetentionPolicy.Kind kind : RetentionPolicy.Kind.values()) {
            final long minimum = kind.minimum(CONTRACT);
            assertInstanceOf(RetentionPolicy.Held.class, RetentionPolicy.until(session,
                    operation(), new RetentionPolicy.Configured(kind, minimum), CONTRACT),
                    kind + " at exactly its minimum was refused");
            final RetentionPolicy.Refused refused = RetentionPolicy.refusalIn(
                    RetentionPolicy.until(session, operation(),
                            new RetentionPolicy.Configured(kind, minimum - 1), CONTRACT))
                    .orElseThrow();
            assertEquals(RetentionPolicy.Refusal.BELOW_THE_MINIMUM, refused.refusal());
            assertTrue(refused.detail().contains(kind.spelling())
                            && refused.detail().contains(String.valueOf(minimum))
                            && refused.detail().contains(String.valueOf(minimum - 1)),
                    refused.detail());
        }
    }

    @Test
    @DisplayName("a longer configured retention is kept to, and one past the maximum is refused")
    void alongerConfiguredRetentionIsKeptTo() throws RepositoryException {
        final Session session = recorded(REQUEST_START);
        final long longer = RetentionPolicy.Kind.RESULT.minimum(CONTRACT) + AN_HOUR;
        assertEquals(REQUEST_START + longer, assertInstanceOf(RetentionPolicy.Held.class,
                RetentionPolicy.until(session, operation(),
                        new RetentionPolicy.Configured(RetentionPolicy.Kind.RESULT, longer),
                        CONTRACT)).retainedUntil().instantUnixMilliseconds(),
                "a deployment that keeps things longer is not kept to its word");
        final long maximum =
                CONTRACT.value(ContractLimit.MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS);
        assertEquals(maximum, assertInstanceOf(RetentionPolicy.Held.class,
                RetentionPolicy.advertised(new RetentionPolicy.Configured(
                        RetentionPolicy.Kind.RESULT, maximum), CONTRACT)).retainedUntil()
                .instantUnixMilliseconds(),
                "a retention at exactly the persisted maximum is not advertised");
        final RetentionPolicy.Refused refused = RetentionPolicy.refusalIn(
                RetentionPolicy.advertised(new RetentionPolicy.Configured(
                        RetentionPolicy.Kind.RESULT, maximum + 1), CONTRACT)).orElseThrow();
        assertEquals(RetentionPolicy.Refusal.PAST_THE_PERSISTED_MAXIMUM, refused.refusal());
        assertTrue(refused.detail().contains(String.valueOf(maximum + 1))
                        && refused.detail().contains(String.valueOf(maximum)),
                refused.detail());
        assertEquals(RetentionPolicy.Refusal.PAST_THE_PERSISTED_MAXIMUM, RetentionPolicy.refusalIn(
                RetentionPolicy.until(session, operation(), new RetentionPolicy.Configured(
                        RetentionPolicy.Kind.RESULT, maximum + 1), CONTRACT)).orElseThrow()
                .refusal(), "a retention nobody could honour was written down anyway");
    }

    @Test
    @DisplayName("two retentions are the same one when they cover the same kind until the same instant")
    void tworetentionsAreTheSameOne() throws RepositoryException {
        final Session session = recorded(REQUEST_START);
        final RetainedUntil once = until(session, RetentionPolicy.Kind.RESULT);
        final RetainedUntil again = until(session, RetentionPolicy.Kind.RESULT);
        assertEquals(once, again, "one record produced two different retentions");
        assertEquals(once.hashCode(), again.hashCode());
        assertEquals(once, once);
        assertNotEquals(once, until(session, RetentionPolicy.Kind.ARTIFACT),
                "two kinds kept for different lengths were said to be the same retention");
        assertNotEquals(once, RetentionPolicy.Kind.RESULT,
                "a retention was said to be the same as something that is not one");
        assertTrue(once.toString().contains(RetentionPolicy.Kind.RESULT.spelling())
                        && once.toString().contains(String.valueOf(
                                once.instantUnixMilliseconds())),
                once.toString());
    }

    @Test
    @DisplayName("nothing produces a retention from a clock reading")
    void nothingProducesAretentionFromAclockReading() {
        for (final Constructor<?> constructor : RetainedUntil.class.getConstructors()) {
            assertEquals(0, constructor.getParameterCount(),
                    "a retention can be constructed from outside the store");
        }
        assertEquals(List.of(), Arrays.stream(RetainedUntil.class.getMethods())
                        .filter(method -> method.getDeclaringClass() == RetainedUntil.class)
                        .filter(method -> java.lang.reflect.Modifier.isStatic(
                                method.getModifiers()))
                        .filter(method -> java.lang.reflect.Modifier.isPublic(
                                method.getModifiers()))
                        .map(Method::getName)
                        .toList(),
                "a retention can be made by a public factory rather than only from a record");
        final String policy = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/RetentionPolicy.java"));
        assertTrue(policy.contains("record.getProperty(REQUEST_START).getLong()"),
                "the retention is measured from something other than the record's request start");
        assertFalse(policy.contains("System.currentTimeMillis"),
                "the retention is measured from this side's clock");
    }

    @Test
    @DisplayName("no minimum is written here, and the record's request start is spelled once")
    void nominimumIsWrittenHere() {
        final String policy = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/RetentionPolicy.java"));
        for (final RetentionPolicy.Kind kind : RetentionPolicy.Kind.values()) {
            assertFalse(policy.contains("= " + kind.minimum(CONTRACT)),
                    kind + "'s minimum is written here rather than read from the contract");
        }
        assertEquals(OperationStore.REQUEST_START, RetentionPolicy.REQUEST_START,
                "the request start is spelled two different ways in one store");
    }

    @Test
    @DisplayName("a retention needs a record, and there is none without one")
    void aretentionNeedsArecord() throws RepositoryException {
        final Session session = prepared();
        assertEquals(RetentionPolicy.Refusal.NO_RECORD, RetentionPolicy.refusalIn(
                RetentionPolicy.until(session, operation(), RetentionPolicy.Kind.RESULT, CONTRACT))
                .orElseThrow().refusal(),
                "a retention was derived for a record nothing holds");
        walked(session, operation().path());
        assertEquals(RetentionPolicy.Refusal.NO_RECORD, RetentionPolicy.refusalIn(
                RetentionPolicy.until(session, operation(), new RetentionPolicy.Configured(
                        RetentionPolicy.Kind.RESULT, RetentionPolicy.Kind.RESULT.minimum(CONTRACT)),
                        CONTRACT)).orElseThrow().refusal(),
                "a record carrying no request start was given a retention anyway");
    }

    private RetainedUntil until(Session session, RetentionPolicy.Kind kind)
            throws RepositoryException {
        return assertInstanceOf(RetentionPolicy.Held.class,
                RetentionPolicy.until(session, operation(), kind, CONTRACT),
                kind + " has no retention").retainedUntil();
    }

    private Session recorded(long requestStart) throws RepositoryException {
        final Session session = prepared();
        OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                LogicalOperation.accepted(identity(), Digest.of(
                        "a submission".getBytes(StandardCharsets.UTF_8)), commandContract(),
                        caller(), requestStart, requestStart, CONTRACT),
                "the record was refused").operation());
        return session;
    }

    private static OperationIdentity identity() {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document("operation.json"), CONTRACT),
                "the operation identity was refused").identity();
    }

    private static CommandContractIdentity commandContract() {
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(document("command-contract.json"),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static StatePath operation() {
        return OperationStore.pathOf(identity());
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-retained-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        final String path = operation().path();
        walked(session, path.substring(0, path.lastIndexOf('/')));
        return session;
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
