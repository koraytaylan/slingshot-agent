// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.AccessClass;
import rs.slingshot.agent.command.ExecutionClass;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;
import rs.slingshot.agent.http.AuthorizationGate;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.route.RouteAlias;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactRecord;
import rs.slingshot.agent.store.ArtifactSlot;

/**
 * The four screens a console shows, and the two links it offers.
 *
 * <p>What is proved here is mostly about the difference between things that look alike. Nothing and
 * unreadable; an expired lease and no lease; a swept artifact and one that was never published; a
 * total nobody counted and a total of nought. Every one of those pairs looks the same on a screen
 * that does not try, and each half sends an operator somewhere different.</p>
 */
final class ConsoleScreenTest {

    private static final List<String> PERMITTED = List.of("slingshot-agent-operators");

    private static final long BOUND = 200;

    private static final String OPERATION = "4ccf24ff28333528";

    /** A digest, stood up once so the fixtures below say what they are about rather than this. */
    private static final String DIGEST =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    @Test
    @DisplayName("the operations list pages what it holds and says when nobody counted the rest")
    void thelistPagesAndDoesNotInventATotal() {
        final List<OperationRow> rows = new ArrayList<>();
        for (int row = 0; row < 5; row++) {
            rows.add(new OperationRow("operation" + row, "create_page", "succeeded", row, 1));
        }
        final ConsolePage<?> page = rendered(new ConsoleDataSource(new OperationListDataSource(
                () -> new OperationListDataSource.Held(rows, new ConsolePage.Unknown()))), 1, 2);
        assertEquals(2, page.rows().size());
        assertEquals(1, page.offset());
        assertEquals(Optional.empty(), page.countedTotal(),
                "a total nobody counted was rendered as a number, and a console showing 0 of 0"
                        + " when it means here-are-two-and-nobody-counted-the-rest teaches an"
                        + " operator that their instance is idle");
    }

    @Test
    @DisplayName("a store that could not be listed is an error rather than an empty list")
    void anunreadableStoreIsNotAnEmptyList() {
        final ConsoleDataSource.Answer answer = new ConsoleDataSource(new OperationListDataSource(
                () -> new OperationListDataSource.Unavailable("the operation store is not there")))
                .answer(request(AuthorizationGate.Standing.A_MEMBER, 0, 50));
        assertInstanceOf(ConsoleDataSource.Unreadable.class, answer,
                "a store that could not be read rendered as no operations, which is a console"
                        + " saying an instance is idle when its own storage is broken");
        final ConsolePage<?> empty = rendered(new ConsoleDataSource(new OperationListDataSource(
                () -> new OperationListDataSource.Held(List.of(),
                        new ConsolePage.Counted(0)))), 0, 50);
        assertTrue(empty.isEmpty(),
                "an instance nobody has submitted work to is an ordinary instance");
    }

    @Test
    @DisplayName("a held lease, an expired one, and no lease are three answers")
    void thethreeLeaseAnswersAreDistinct() {
        assertInstanceOf(OperationDetail.Held.class,
                OperationDetail.leaseAt("node-a", 2_000, 1_000));
        assertInstanceOf(OperationDetail.Expired.class,
                OperationDetail.leaseAt("node-a", 1_000, 2_000),
                "a hold that ran out was reported as still held, and an expired lease is a node"
                        + " that stopped without finishing");
        assertInstanceOf(OperationDetail.Unheld.class, new OperationDetail.Unheld());
        assertEquals("node-a",
                ((OperationDetail.Expired) OperationDetail.leaseAt("node-a", 1_000, 2_000))
                        .worker(),
                "an expired lease does not say which node held it, which is the first thing"
                        + " anybody asks about a stuck operation");
    }

    @Test
    @DisplayName("an operation nothing is at is an empty page rather than a failure")
    void anabsentOperationIsAnAnswer() {
        final ConsolePage<?> absent = rendered(new ConsoleDataSource(
                new OperationDetailDataSource(OPERATION,
                        identifier -> new OperationDetailDataSource.Absent())), 0, 50);
        assertTrue(absent.isEmpty(),
                "an identifier whose operation the sweep has collected was reported as a failure,"
                        + " and that is the retention doing what somebody configured it to do");
        assertInstanceOf(ConsoleDataSource.Unreadable.class,
                new ConsoleDataSource(new OperationDetailDataSource(OPERATION,
                        identifier -> new OperationDetailDataSource.Unavailable("no ledger")))
                        .answer(request(AuthorizationGate.Standing.A_MEMBER, 0, 50)),
                "a ledger that could not be read rendered as an operation that is not there");
    }

    @Test
    @DisplayName("an operation the stores hold is rendered as the one row it is")
    void afoundOperationIsOneRow() {
        final OperationDetail detail = new OperationDetail(
                new OperationRow(OPERATION, "create_page", "running", 1_000, 1),
                "0123456789abcdef", "create_page/1.0.0", List.of("submitted"),
                List.of(new OperationDetail.Attempt(1, "node-a", "running")),
                OperationDetail.leaseAt("node-a", 9_000, 1_000), List.of());
        final ConsolePage<?> page = rendered(new ConsoleDataSource(
                new OperationDetailDataSource(OPERATION,
                        identifier -> new OperationDetailDataSource.Found(detail))), 0, 50);
        assertEquals(1, page.rows().size(),
                "one operation was rendered as something other than one row");
        assertEquals(detail, page.rows().getFirst());
        assertEquals(Optional.of(1L), page.countedTotal(),
                "a page holding one operation did not say so");
    }

    @Test
    @DisplayName("a detail page carries every part, and every absent part says so")
    void adetailCarriesEveryPart() {
        final OperationDetail detail = new OperationDetail(
                new OperationRow(OPERATION, "create_page", "failed", 1_000, 2),
                "0123456789abcdef", "create_page/1.0.0", List.of("submitted", "failed"),
                List.of(new OperationDetail.Attempt(1, "node-a", "failed"),
                        new OperationDetail.Attempt(2, "node-b", "failed")),
                new OperationDetail.Unheld(), List.of());
        assertEquals(2, detail.attempts().size());
        assertEquals(List.of("submitted", "failed"), detail.events(),
                "the events are not the ledger in sequence order");
        assertEquals(List.of(), detail.artifacts(),
                "an operation that published nothing was given artifacts");
        assertTrue(!detail.submittedDigest().isEmpty() && !detail.commandContract().isEmpty(),
                "the page does not say what was submitted or which command it is, so a reader"
                        + " cannot tell a resend from a different command under the same"
                        + " identifier without leaving the page");
        assertEquals("node-b", detail.attempts().get(1).worker(),
                "an attempt does not say which node made it, and four attempts on four nodes is a"
                        + " cluster passing work around while four on one is a command that keeps"
                        + " failing");
    }

    @Test
    @DisplayName("an artifact is linked through the route, never through a repository path")
    void anartifactIsLinkedThroughTheRoute() {
        final ArtifactLink.Offer offer = ArtifactLink.offer(OPERATION, record(),
                ArtifactLink.Retention.HELD, ConsoleAuthority.Visibility.SHOWN);
        final ArtifactLink.Linked linked = assertInstanceOf(ArtifactLink.Linked.class, offer,
                "an artifact that is still there was not linked");
        assertTrue(linked.address().startsWith(pathOf(ArtifactLink.ROUTE_NAME)),
                "the link does not address the route the client already uses: " + linked.address());
        assertTrue(!linked.address().contains("/var/") && !linked.address().contains("/content/"),
                "the link carries a repository path, which is either useless or a way to read the"
                        + " agent's own storage through a console link: " + linked.address());
        assertEquals(1_024, linked.byteCount());
        assertTrue(!linked.digest().isEmpty(),
                "the page does not say what the artifact hashes to, so a downloader has to trust"
                        + " the page rather than check what they received");
    }

    @Test
    @DisplayName("a swept artifact is shown as expired, and one a viewer may not fetch is not shown")
    void thetwoNonLinksAreDistinct() {
        assertInstanceOf(ArtifactLink.Expired.class,
                ArtifactLink.offer(OPERATION, record(), ArtifactLink.Retention.SWEPT,
                        ConsoleAuthority.Visibility.SHOWN),
                "an artifact retention has already taken was offered as a link, and a link that"
                        + " fails when clicked teaches an operator that downloads are broken");
        assertInstanceOf(ArtifactLink.Withheld.class,
                ArtifactLink.offer(OPERATION, record(), ArtifactLink.Retention.HELD,
                        ConsoleAuthority.Visibility.HIDDEN),
                "a viewer the route would refuse was offered a link, which sends them to a"
                        + " refusal and leaves them thinking the download is broken rather than"
                        + " that it is not theirs");
    }

    @Test
    @DisplayName("a running operation is followed on the client's own route, and an ended one is not")
    void thetailUsesTheClientsOwnRoute() {
        final TailSubscription.Offer running = TailSubscription.offer(OPERATION,
                TailSubscription.Progress.RUNNING, ConsoleAuthority.Visibility.SHOWN);
        assertTrue(assertInstanceOf(TailSubscription.Followable.class, running).address()
                        .startsWith(pathOf(TailSubscription.ROUTE_NAME)),
                "the console follows its own stream rather than the one a client follows, so the"
                        + " two could show different accounts of the same operation");
        assertInstanceOf(TailSubscription.Finished.class,
                TailSubscription.offer(OPERATION, TailSubscription.Progress.ENDED,
                        ConsoleAuthority.Visibility.SHOWN),
                "an operation that ended an hour ago was offered a stream, which spends the"
                        + " instance's bounded stream budget on saying nothing");
        assertInstanceOf(TailSubscription.Withheld.class,
                TailSubscription.offer(OPERATION, TailSubscription.Progress.RUNNING,
                        ConsoleAuthority.Visibility.HIDDEN));
    }

    @Test
    @DisplayName("a sweep that has never run says so rather than showing an instant of nought")
    void aneverRunSweepSaysNever() {
        final List<MaintenanceDataSource.Reading> readings = MaintenanceDataSource.readingsOf(
                new MaintenanceDataSource.Held("generation-2", 1, 40, 100,
                        MaintenanceDataSource.NEVER_SWEPT));
        assertEquals(MaintenanceDataSource.NEVER, readings.getLast().value(),
                "a sweep that has never run was shown as an instant of nought, which reads as"
                        + " nineteen-seventy and sends somebody looking for a clock problem");
        assertEquals("generation", readings.getFirst().name());
        assertEquals(5, readings.size(),
                "the maintenance screen no longer shows every number an operator only wants once"
                        + " and always urgently");
        assertInstanceOf(ConsoleDataSource.Unreadable.class,
                new ConsoleDataSource(new MaintenanceDataSource(
                        () -> new MaintenanceDataSource.Unavailable("no generation store")))
                        .answer(request(AuthorizationGate.Standing.A_MEMBER, 0, 50)));
    }

    @Test
    @DisplayName("the identity screen renders discovery's own digests and never a second copy")
    void theidentityScreenReadsOneSource() {
        final AdvertisedCapabilities capabilities = capabilities();
        final List<MaintenanceDataSource.Reading> readings = BuildIdentityDataSource.readingsOf(
                capabilities, new BuildIdentityDataSource.Build("0.1.0", "bf4ebf0", "aem-6-5-lts",
                        BuildIdentityDataSource.Claim.UNCLAIMED), List.of(), List.of());
        assertEquals(capabilities.transportContractDigest().rendered(),
                valueOf(readings, "transport_contract_digest"),
                "the page renders a transport digest that is not the one discovery answers, so a"
                        + " client comparing the two would be told it is the one that is wrong");
        assertEquals(capabilities.canonicalContractDigest().rendered(),
                valueOf(readings, "canonical_contract_digest"));
        assertEquals(String.valueOf(capabilities.generation().number()),
                valueOf(readings, "event_store_generation"));
        assertEquals(BuildIdentityDataSource.NOT_READY, valueOf(readings, "continuation_authority"),
                "an authority that cannot issue a token was rendered as one that can, and a paged"
                        + " query is refused rather than answered when it cannot");
        assertEquals(BuildIdentityDataSource.UNCLAIMED, valueOf(readings, "deployment_claim"),
                "a build running on a row it does not claim did not say so, and that is the first"
                        + " thing worth knowing when something does not work");
        assertEquals("version", readings.getFirst().name());
    }

    @Test
    @DisplayName("no field on this page could hold a second copy of what discovery answers")
    void theidentityPageCannotBeGivenASecondSource() {
        final List<String> carried = java.util.Arrays.stream(
                        BuildIdentityDataSource.Build.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .filter(name -> name.contains("digest") || name.contains("generation")
                        || name.contains("ready"))
                .toList();
        assertEquals(List.of(), carried,
                "the page can be handed its own copy of something discovery answers, and the day"
                        + " the two differ the client comparing them is told it is wrong: "
                        + carried);
    }

    @Test
    @DisplayName("every alias is rendered with its canonical route, client version and correction")
    void theidentityScreenRendersEveryAliasCompletely() {
        final RouteAlias alias = new RouteAlias("/bin/slingshot/agent/capabilities",
                "capabilities", "0.9.0", "the client asks for the singular spelling",
                "carried for a client that has not caught up");
        final List<MaintenanceDataSource.Reading> readings = BuildIdentityDataSource.readingsOf(
                capabilities(), build(), List.of(alias), List.of());
        final String rendered = valueOf(readings, BuildIdentityDataSource.ALIAS + alias.path());
        assertTrue(rendered.contains("canonical=" + alias.routeName())
                        && rendered.contains("client_version=" + alias.clientVersion())
                        && rendered.contains("pending_correction=" + alias.pendingCorrection()),
                "an alias is rendered without one of the three things that say when it may go,"
                        + " which makes it a second path with no end date: " + rendered);
    }

    @Test
    @DisplayName("commands are rendered in the registry's own order with every declared field")
    void theidentityScreenRendersCommandsInRegistryOrder() {
        final List<RegistryRow> commands = List.of(
                row("query_paths", RegistryRow.OperationKey.REFUSED),
                row("create_page", RegistryRow.OperationKey.REQUIRED));
        final List<MaintenanceDataSource.Reading> readings =
                BuildIdentityDataSource.readingsOf(capabilities(), build(), List.of(), commands);
        final List<String> named = readings.stream()
                .map(MaintenanceDataSource.Reading::name)
                .filter(name -> name.startsWith(BuildIdentityDataSource.COMMAND))
                .toList();
        assertEquals(List.of(BuildIdentityDataSource.COMMAND + "query_paths",
                        BuildIdentityDataSource.COMMAND + "create_page"), named,
                "the commands are no longer in the registry's own order, and an operator comparing"
                        + " this page with the registry is comparing two orders");
        final String rendered =
                valueOf(readings, BuildIdentityDataSource.COMMAND + "create_page");
        assertTrue(rendered.contains("contract_version=1.0.0")
                        && rendered.contains("access_class=")
                        && rendered.contains("operation_key=required")
                        && rendered.contains("result_bytes=4096"),
                "a command is rendered without one of the fields that decide how it may be called: "
                        + rendered);
    }

    @Test
    @DisplayName("retention is shown per kind, with every bound read from the contract")
    void theretentionScreenIsPerKindAndContractBound() {
        final AgentContract contract = contract();
        final List<MaintenanceDataSource.Reading> readings = RetentionDataSource.readingsOf(
                new RetentionDataSource.Retention(List.of(
                        held(AccountedQuantity.EVENT_ROWS, 1_200, 40),
                        held(AccountedQuantity.ARTIFACT_ROWS, 96, 96))), contract);
        assertEquals(String.valueOf(AccountedQuantity.EVENT_ROWS.admissibleTotal(contract)),
                valueOf(readings, AccountedQuantity.EVENT_ROWS.spelling()
                        + RetentionDataSource.BOUND),
                "a bound was written down here rather than read from the contract, so changing the"
                        + " contract would not change the page");
        assertEquals("1296", valueOf(readings, RetentionDataSource.RETAINED_TOTAL),
                "the total is not the sum of the parts, which makes it a number that can say"
                        + " something the parts do not");
        assertTrue(readings.stream().map(MaintenanceDataSource.Reading::name)
                        .anyMatch(name -> name.startsWith(AccountedQuantity.ARTIFACT_ROWS
                                .spelling())),
                "the kinds are not shown separately, and the fix for too many events and the fix"
                        + " for too many artifacts are different things done by different people");
    }

    @Test
    @DisplayName("a kind full expiry would not rescue is shown as needing a decision")
    void theretentionScreenNamesWhatPatienceWillNotFix() {
        final AgentContract contract = contract();
        final long bound = AccountedQuantity.EVENT_ROWS.admissibleTotal(contract);
        assertEquals(RetentionDataSource.NEEDS_A_DECISION,
                valueOf(RetentionDataSource.readingsOf(new RetentionDataSource.Retention(List.of(
                                held(AccountedQuantity.EVENT_ROWS, bound + 10, 1))), contract),
                        AccountedQuantity.EVENT_ROWS.spelling()
                                + RetentionDataSource.AFTER_FULL_EXPIRY),
                "a store that would still be over its bound after everything eligible had expired"
                        + " was shown as something patience fixes, and it is not");
        assertEquals(RetentionDataSource.WITHIN_BOUND_AFTER_EXPIRY,
                valueOf(RetentionDataSource.readingsOf(new RetentionDataSource.Retention(List.of(
                                held(AccountedQuantity.EVENT_ROWS, bound + 10, 20))), contract),
                        AccountedQuantity.EVENT_ROWS.spelling()
                                + RetentionDataSource.AFTER_FULL_EXPIRY),
                "a store the next sweep will bring back within its bound was shown as needing a"
                        + " decision, which sends somebody to change a bound that is fine");
        assertEquals(6, rendered(new ConsoleDataSource(new RetentionDataSource(
                                () -> new RetentionDataSource.Retention(List.of(
                                        held(AccountedQuantity.EVENT_ROWS, 0, 0))), contract)),
                        0, 50).rows().size(),
                "one kind is no longer five readings and the sum of the parts");
    }

    @Test
    @DisplayName("every screen refuses a viewer who may not see the console, before reading anything")
    void everyscreenRefusesAnUnpermittedViewer() {
        final List<ConsoleDataSource.Rows> screens = List.of(
                new OperationListDataSource(() -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }),
                new OperationDetailDataSource(OPERATION, identifier -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }),
                new MaintenanceDataSource(() -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }),
                new BuildIdentityDataSource(() -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }, () -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }, () -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }, () -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }),
                new RetentionDataSource(() -> {
                    throw new IllegalStateException("the store was read for a refused viewer");
                }, contract()));
        screens.forEach(screen -> assertInstanceOf(ConsoleDataSource.Denied.class,
                new ConsoleDataSource(screen)
                        .answer(request(AuthorizationGate.Standing.NOT_A_MEMBER, 0, 50)),
                screen.getClass().getSimpleName() + " answered a viewer who may not see it"));
    }

    private static ArtifactRecord record() {
        return new ArtifactRecord(
                assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of("result"),
                        "the slot was refused").slot(),
                1_024,
                assertInstanceOf(DigestValue.Held.class, DigestValue.of(DIGEST),
                        "the digest was refused").digest(),
                2_000);
    }

    private static ConsolePage<?> rendered(ConsoleDataSource source, long offset, long window) {
        return assertInstanceOf(ConsoleDataSource.Rendered.class,
                source.answer(request(AuthorizationGate.Standing.A_MEMBER, offset, window)),
                "the screen was refused").page();
    }

    /**
     * Where one named route sits, read from the committed table exactly as the console reads it.
     *
     * @param name the route's own name
     * @return its path
     */
    private static String pathOf(String name) {
        return assertInstanceOf(AgentRouteTable.Loaded.class, AgentRouteTable.load(),
                "the committed route table did not load").table().route(name).path();
    }

    private static ConsoleDataSource.Request request(AuthorizationGate.Standing standing,
                                                     long offset, long window) {
        return new ConsoleDataSource.Request(PERMITTED, group -> standing, offset, window, BOUND);
    }

    /**
     * What one reading says, found by the name the page gives it.
     *
     * @param readings the rendered page
     * @param name the reading to look for
     * @return its value
     */
    private static String valueOf(List<MaintenanceDataSource.Reading> readings, String name) {
        return readings.stream()
                .filter(reading -> name.equals(reading.name()))
                .map(MaintenanceDataSource.Reading::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not on the page: " + readings));
    }

    /** Discovery's own answer, with an authority that cannot issue a token. */
    private static AdvertisedCapabilities capabilities() {
        return new AdvertisedCapabilities(generation(), digest(DIGEST), List.of(),
                AdvertisedCapabilities.ContinuationAuthority.NOT_READY, digest(DIGEST));
    }

    private static DigestValue digest(String rendered) {
        return assertInstanceOf(DigestValue.Held.class, DigestValue.of(rendered),
                "the digest was refused").digest();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(1),
                "the generation was refused").generation();
    }

    private static BuildIdentityDataSource.Build build() {
        return new BuildIdentityDataSource.Build("0.1.0", "bf4ebf0", "aem-cloud-service",
                BuildIdentityDataSource.Claim.CLAIMED);
    }

    private static RetentionDataSource.Held held(AccountedQuantity quantity, long retained,
                                                 long releasable) {
        return new RetentionDataSource.Held(quantity, retained, releasable, 9_000);
    }

    private static RegistryRow row(String wireName, RegistryRow.OperationKey operationKey) {
        return new RegistryRow(wireName, "1.0.0", AccessClass.READ, operationKey, 4_096,
                List.of("argument_refused"), DIGEST, DIGEST, DIGEST, 0,
                ExecutionClass.IMMEDIATE);
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract this build carries did not authenticate").contract();
    }
}
