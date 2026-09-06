// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;
import org.junit.jupiter.api.Test;

/** Covers the shared paging decision at its initial, continuation, and refusal boundaries. */
final class PagingSupportTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    @Test
    void initialWindowReturnsRowsWithoutContinuationAuthority() {
        final PagingSupport.Outcome<String> outcome = PagingSupport.page(
                List.of("a", "b"), new ResultWindow.Initial(0, 10), "query_paths", empty(),
                context(), CONTRACT);
        assertTrue(outcome instanceof PagingSupport.Accepted<?>);
        final PagingSupport.Accepted<?> accepted = (PagingSupport.Accepted<?>) outcome;
        assertEquals(List.of("a", "b"), accepted.page().rows());
        assertEquals("", accepted.page().continuationToken());
    }

    @Test
    void continuationRequiresAuthorityEvenWhenItsTokenIsWellFormed() {
        final PagingSupport.Outcome<String> outcome = PagingSupport.page(
                List.of("a", "b"), new ResultWindow.Continuation("bad-token"), "query_paths",
                empty(), context(), CONTRACT);
        assertTrue(outcome instanceof PagingSupport.Refused<?>);
        final PagingSupport.Refused<?> refused = (PagingSupport.Refused<?>) outcome;
        assertEquals("continuation_token_integrity_invalid", refused.category());
    }

    @Test
    void availableAuthorityIssuesASignedSuccessorToken() {
        final PagingSupport.Outcome<String> outcome = PagingSupport.page(
                List.of("a", "b"), new ResultWindow.Initial(0, 1), "query_paths", empty(),
                availableContext(), CONTRACT);
        final PagingSupport.Accepted<?> accepted = (PagingSupport.Accepted<?>) outcome;
        assertEquals(1, accepted.page().rows().size());
        assertFalse(accepted.page().continuationToken().isEmpty());
    }

    private static DocumentValue.Mapping empty() {
        return new DocumentValue.Mapping(new LinkedHashMap<>());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static CallerContext availableContext() {
        final ContinuationKeyAuthority authority = new ContinuationKeyAuthority() {
            @Override
            public ReadOutcome read() {
                return new Read(KeyRing.initial("paging-test-key"));
            }

            @Override
            public WriteOutcome compareAndSet(KeyRing expected, KeyRing next, Lease lease,
                                              long nowUnixMilliseconds) {
                return new NotWritten(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT, "test"));
            }
        };
        final DigestValue target = assertInstanceOf(DigestValue.Held.class,
                DigestValue.of("b".repeat(DigestValue.RENDERED_LENGTH))).digest();
        final EventStoreGeneration generation = assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST)).generation();
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT),
                new CallerContext.Available(authority, target, generation, 1_000L));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class, AgentOperationIdentifier.of(
                "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8", CONTRACT))
                .identifier();
    }
}
