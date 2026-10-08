// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.stream.ElapsedTime;

/** The real walker applies its duration bound even to the last resource. */
@ExtendWith(SlingContextExtension.class)
final class BoundedWalkTest {

    private static final long TIME_LIMIT = 5_000;
    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void theLastVisitorMayFinishExactlyAtTheLimit() {
        assertTrue(walkTaking(TIME_LIMIT));
    }

    @Test
    void theLastVisitorCannotExceedTheLimitUnobserved() {
        assertFalse(walkTaking(TIME_LIMIT + 1));
    }

    @Test
    void exactlyTheNodeAllowanceCompletesAndOneMoreRefuses() {
        final var root = sling.create().resource("/content/synthetic-walk-boundary");
        sling.create().resource(root.getPath() + "/synthetic-child");
        final var visited = new AtomicLong();
        assertTrue(BoundedWalk.every(root, context(2), resource -> true,
                resource -> visited.incrementAndGet()));
        assertEquals(2, visited.get());
        visited.set(0);
        assertFalse(BoundedWalk.every(root, context(1), resource -> true,
                resource -> visited.incrementAndGet()));
        assertEquals(1, visited.get());
    }

    @Test
    void prunedDescendantsConsumeNoNodeAllowance() {
        final var root = sling.create().resource("/content/synthetic-pruned-walk");
        sling.create().resource(root.getPath() + "/synthetic-child");
        final var visited = new AtomicLong();
        assertTrue(BoundedWalk.every(root, context(1), resource -> false,
                resource -> visited.incrementAndGet()));
        assertEquals(1, visited.get());
    }

    @Test
    void alreadyExhaustedDurationDoesNotVisitTheRoot() {
        final var root = sling.create().resource("/content/synthetic-exhausted-walk");
        final var visited = new AtomicLong();
        final var monotonic = new AtomicLong();
        final var elapsed = ElapsedTime.start(monotonic::get);
        monotonic.set(TIME_LIMIT + 1);
        assertFalse(BoundedWalk.every(root, context(1), resource -> true,
                resource -> visited.incrementAndGet(), elapsed));
        assertEquals(0, visited.get());
    }

    private boolean walkTaking(long milliseconds) {
        final Resource root = sling.create().resource("/content/test");
        final AtomicLong monotonic = new AtomicLong();
        final ElapsedTime elapsed = ElapsedTime.start(monotonic::get);
        return BoundedWalk.every(root, context(), resource -> true,
                resource -> monotonic.addAndGet(milliseconds), elapsed);
    }

    private static CallerContext context() {
        return context(Budget.discovery(CONTRACT).limit());
    }

    private static CallerContext context(long nodes) {
        final AgentOperationIdentifier operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT)).identifier();
        return new CallerContext(operation, new Budget(Budget.Kind.DISCOVERY, nodes),
                new Budget(Budget.Kind.TIME, TIME_LIMIT),
                new Budget(Budget.Kind.RESULT, CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }
}
