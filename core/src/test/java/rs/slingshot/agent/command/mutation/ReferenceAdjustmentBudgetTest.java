// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.page.MovePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Move admission counts actual matching values before changing source or references. */
@ExtendWith(SlingContextExtension.class)
final class ReferenceAdjustmentBudgetTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final int BOUND = Math.toIntExact(
            CONTRACT.value(ContractLimit.MAXIMUM_ADJUSTED_REFERENCES));
    private static final String SOURCE = "/content/synthetic-budget-source/item";
    private static final String DESTINATION = "/content/synthetic-budget-destination/item";
    private static final String REFERENCES = "/content/synthetic-budget-references";
    private static final String OTHER = "/content/synthetic-other/item";
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void oneMultiplePropertyOnePastTheBoundRefusesBeforeMovement() {
        plant();
        final String[] links = repeated(SOURCE, BOUND + 1);
        sling.create().resource(REFERENCES, Map.of("links", links));

        refuse();

        assertArrayEquals(links, required(REFERENCES).getValueMap().get("links", String[].class));
    }

    @Test
    void aScalarAlongsideAMultiplePropertyCountsTowardTheSameBound() {
        plant();
        final String[] links = repeated(SOURCE, BOUND);
        sling.create().resource(REFERENCES, Map.of("links", links, "link", SOURCE));

        refuse();

        assertArrayEquals(links, required(REFERENCES).getValueMap().get("links", String[].class));
        assertEquals(SOURCE, required(REFERENCES).getValueMap().get("link"));
    }

    @Test
    void matchesAcrossPropertiesAndResourcesShareOneAdjustmentBudget() {
        plant();
        final String[] first = repeated(SOURCE, BOUND / 2);
        final String[] second = repeated(SOURCE, BOUND - first.length);
        sling.create().resource(REFERENCES, Map.of("first", first, "link", SOURCE));
        sling.create().resource(REFERENCES + "/child", Map.of("second", second));

        refuse();

        assertArrayEquals(first, required(REFERENCES).getValueMap().get("first", String[].class));
        assertEquals(SOURCE, required(REFERENCES).getValueMap().get("link"));
        assertArrayEquals(second, required(REFERENCES + "/child")
                .getValueMap().get("second", String[].class));
    }

    @Test
    void exactlyTheBoundMovesAndCountsOnlyMatchingArrayValues() {
        plant();
        final String[] links = repeated(SOURCE, BOUND + 2);
        links[0] = OTHER;
        links[links.length - 1] = OTHER;
        sling.create().resource(REFERENCES, Map.of("links", links));

        accept(true, BOUND);

        final String[] expected = repeated(DESTINATION, BOUND + 2);
        expected[0] = OTHER;
        expected[expected.length - 1] = OTHER;
        assertArrayEquals(expected, required(REFERENCES).getValueMap().get("links", String[].class));
    }

    @Test
    void exactlyTheBoundAcrossPropertiesAndResourcesStillCommitsOnce() {
        plant();
        final int first = BOUND / 2;
        final int second = BOUND - first - 1;
        sling.create().resource(REFERENCES, Map.of("first", repeated(SOURCE, first), "link", SOURCE));
        sling.create().resource(REFERENCES + "/child", Map.of("second", repeated(SOURCE, second)));

        accept(true, BOUND);

        assertArrayEquals(repeated(DESTINATION, first), required(REFERENCES)
                .getValueMap().get("first", String[].class));
        assertEquals(DESTINATION, required(REFERENCES).getValueMap().get("link"));
        assertArrayEquals(repeated(DESTINATION, second), required(REFERENCES + "/child")
                .getValueMap().get("second", String[].class));
    }

    @Test
    void explicitlyLeavingReferencesDoesNotApplyAnAdjustmentBudget() {
        plant();
        final String[] links = repeated(SOURCE, BOUND + 1);
        sling.create().resource(REFERENCES, Map.of("links", links));

        accept(false, 0);

        assertArrayEquals(links, required(REFERENCES).getValueMap().get("links", String[].class));
    }

    private void refuse() {
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver())) {
            final CommandHandler.Failed failure = assertInstanceOf(CommandHandler.Failed.class,
                    move(caller, true));
            assertEquals(MovePageHandler.ADJUSTMENT_BUDGET_EXCEEDED, failure.category());
            assertEquals(0, caller.moves.get());
            assertEquals(0, caller.commits.get());
            assertNotNull(sling.resourceResolver().getResource(SOURCE));
            assertNull(sling.resourceResolver().getResource(DESTINATION));
        }
    }

    private void accept(boolean adjust, long count) {
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver())) {
            final CommandHandler.Produced result = assertInstanceOf(CommandHandler.Produced.class,
                    move(caller, adjust));
            assertEquals(new DocumentValue.Whole(count), result.result()
                    .member("adjusted_reference_count").orElseThrow());
            assertEquals(1, caller.moves.get());
            assertEquals(1, caller.commits.get());
            assertNull(sling.resourceResolver().getResource(SOURCE));
            assertNotNull(sling.resourceResolver().getResource(DESTINATION));
        }
    }

    private Resource required(String path) {
        return java.util.Objects.requireNonNull(sling.resourceResolver().getResource(path),
                "the synthetic fixture resource");
    }

    private void plant() {
        sling.create().resource(SOURCE, Map.of("jcr:primaryType", "cq:Page"));
        sling.create().resource("/content/synthetic-budget-destination");
    }

    private static String[] repeated(String value, int count) {
        final String[] values = new String[count];
        Arrays.fill(values, value);
        return values;
    }

    private static CommandHandler.Answer move(ResourceResolver caller, boolean adjust) {
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(MoveRequest.SOURCE_PATH, new DocumentValue.Text(SOURCE));
        members.put(MoveRequest.DESTINATION_PATH, new DocumentValue.Text(DESTINATION));
        members.put(MoveRequest.ADJUST_REFERENCES, new DocumentValue.Flag(adjust
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        final CommandHandler handler = new MovePageHandler(CONTRACT);
        final var operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT)).identifier();
        final var context = new CallerContext(operation, Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
        return handler.run(new DocumentValue.Mapping(members), caller, context);
    }

    private static final class FixtureCaller extends ResourceResolverWrapper {

        private final AtomicInteger moves = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

        private FixtureCaller(ResourceResolver delegate) {
            super(delegate);
        }

        @Override
        public Resource move(String source, String destination) throws PersistenceException {
            moves.incrementAndGet();
            return super.move(source, destination);
        }

        @Override
        public void commit() throws PersistenceException {
            commits.incrementAndGet();
            super.commit();
        }
    }
}
