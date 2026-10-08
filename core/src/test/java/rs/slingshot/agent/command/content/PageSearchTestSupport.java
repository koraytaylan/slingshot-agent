// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;

/** Synthetic caller authority and independently owned read-only resolvers for search fixtures. */
final class PageSearchTestSupport {

    private PageSearchTestSupport() {
    }

    static ResourceResolver readOnly(ResourceResolver borrowed) {
        try {
            borrowed.commit();
        } catch (final org.apache.sling.api.resource.PersistenceException failure) {
            throw new AssertionError(failure);
        }
        return new ResourceResolverWrapper(ReadOnlyResolver.around(borrowed)) {
            @Override
            public ResourceResolver clone(Map<String, Object> authentication) throws LoginException {
                return ReadOnlyResolver.around(borrowed.clone(authentication));
            }
        };
    }

    static CallerContext context(AgentContract contract, AgentOperationIdentifier operation, long nodes) {
        final var target = assertInstanceOf(DigestValue.Held.class,
                DigestValue.of("b".repeat(DigestValue.RENDERED_LENGTH))).digest();
        final var generation = assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST)).generation();
        return new CallerContext(operation, new Budget(Budget.Kind.DISCOVERY, nodes), Budget.time(contract),
                new Budget(Budget.Kind.RESULT, contract.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(contract),
                new CallerContext.Available(authority(), target, generation, 1_000L));
    }

    private static ContinuationKeyAuthority authority() {
        return new ContinuationKeyAuthority() {
            @Override
            public ReadOutcome read() {
                return new Read(KeyRing.initial("synthetic-page-search-key"));
            }

            @Override
            public WriteOutcome compareAndSet(KeyRing expected, KeyRing next, Lease lease,
                                              long nowUnixMilliseconds) {
                return new NotWritten(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT, "test"));
            }
        };
    }
}
