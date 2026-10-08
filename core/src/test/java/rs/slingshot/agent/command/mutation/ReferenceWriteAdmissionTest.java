// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
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

/** A caller-visible reference without a writable provider must refuse before moving anything. */
@ExtendWith(SlingContextExtension.class)
final class ReferenceWriteAdmissionTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String SOURCE = "/content/synthetic-source/item";
    private static final String DESTINATION = "/content/synthetic-destination/item";
    private static final String WRITABLE = "/content/synthetic-writable";
    private static final String READABLE = "/content/synthetic-readable";
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void aReadableReferenceWithoutAWritableMapRefusesBeforeTheMove() {
        plant();
        sling.create().resource(READABLE, Map.of("link", SOURCE));
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver(), Set.of(READABLE))) {

            final CommandHandler.Failed failure = assertInstanceOf(CommandHandler.Failed.class,
                    move(caller, true));

            assertEquals(MovePageHandler.COMMIT_FAILED, failure.category());
            untouched(caller);
            assertEquals(SOURCE, required(READABLE).getValueMap().get("link"));
        }
    }

    @Test
    void aLateUnwritableReferenceLeavesEarlierWritableReferencesAndTheSourceUntouched() {
        plant();
        sling.create().resource(WRITABLE, Map.of("link", SOURCE, "links", new String[]{SOURCE, SOURCE}));
        sling.create().resource(READABLE, Map.of("link", SOURCE));
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver(), Set.of(READABLE))) {

            final CommandHandler.Failed failure = assertInstanceOf(CommandHandler.Failed.class,
                    move(caller, true));

            assertEquals(MovePageHandler.COMMIT_FAILED, failure.category());
            untouched(caller);
            assertEquals(SOURCE, required(WRITABLE).getValueMap().get("link"));
            assertEquals(List.of(SOURCE, SOURCE),
                    List.of((String[]) required(WRITABLE).getValueMap().get("links")));
        }
    }

    @Test
    void leavingReferencesKeepsTheirReadOnlyProviderOutsideTheRequestedMutation() {
        plant();
        sling.create().resource(READABLE, Map.of("link", SOURCE));
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver(), Set.of(READABLE))) {

            assertInstanceOf(CommandHandler.Produced.class, move(caller, false));

            assertEquals(1, caller.moves.get());
            assertEquals(1, caller.commits.get());
            assertNull(sling.resourceResolver().getResource(SOURCE));
            assertNotNull(sling.resourceResolver().getResource(DESTINATION));
            assertEquals(SOURCE, required(READABLE).getValueMap().get("link"));
        }
    }

    @Test
    void completeWritableReferencesStillMoveAndRepointInOneCommit() {
        plant();
        sling.create().resource(WRITABLE, Map.of("link", SOURCE, "links", new String[]{SOURCE, SOURCE}));
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver(), Set.of())) {

            final CommandHandler.Produced result = assertInstanceOf(CommandHandler.Produced.class,
                    move(caller, true));

            assertEquals(new DocumentValue.Whole(3), result.result()
                    .member("adjusted_reference_count").orElseThrow());
            assertEquals(1, caller.moves.get());
            assertEquals(1, caller.commits.get());
            assertEquals(DESTINATION, required(WRITABLE)
                    .getValueMap().get("link"));
            assertEquals(List.of(DESTINATION, DESTINATION),
                    List.of((String[]) required(WRITABLE).getValueMap().get("links")));
        }
    }

    private Resource required(String path) {
        return java.util.Objects.requireNonNull(sling.resourceResolver().getResource(path),
                "the synthetic fixture resource");
    }

    private void plant() {
        sling.create().resource(SOURCE, Map.of("jcr:primaryType", "cq:Page", "synthetic-marker", "retained"));
        sling.create().resource("/content/synthetic-destination");
    }

    private void untouched(FixtureCaller caller) {
        assertEquals(0, caller.moves.get(), "an unadjustable reference was discovered after movement");
        assertEquals(0, caller.commits.get(), "a refused move still committed");
        assertNotNull(sling.resourceResolver().getResource(SOURCE));
        assertNull(sling.resourceResolver().getResource(DESTINATION));
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

        private final Set<String> unwritable;
        private final AtomicInteger moves = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

        private FixtureCaller(ResourceResolver delegate, Set<String> unwritable) {
            super(delegate);
            this.unwritable = unwritable;
        }

        @Override
        public Resource getResource(String path) {
            final Resource held = super.getResource(path);
            return held == null ? null : wrap(held);
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

        private Resource wrap(Resource held) {
            return new ResourceWrapper(held) {
                @Override
                public Iterator<Resource> listChildren() {
                    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                            super.listChildren(), 0), false).map(FixtureCaller.this::wrap).iterator();
                }

                @Override
                public <Adapter> Adapter adaptTo(Class<Adapter> type) {
                    return type == ModifiableValueMap.class && unwritable.contains(getPath())
                            ? null : super.adaptTo(type);
                }
            };
        }
    }
}
