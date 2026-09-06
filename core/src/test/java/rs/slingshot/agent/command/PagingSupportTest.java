// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
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

    private static DocumentValue.Mapping empty() {
        return new DocumentValue.Mapping(new LinkedHashMap<>());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class, AgentOperationIdentifier.of(
                "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8", CONTRACT))
                .identifier();
    }
}
