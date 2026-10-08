// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import rs.slingshot.agent.command.configuration.ConfigurationHandler;
import rs.slingshot.agent.command.content.AuthoringCatalogHandler;
import rs.slingshot.agent.command.content.ChildListingHandler;
import rs.slingshot.agent.command.content.DiscoveryRegistry;
import rs.slingshot.agent.command.content.FindAssetsByMetadataHandler;
import rs.slingshot.agent.command.content.FindAssetsReferencedByPageHandler;
import rs.slingshot.agent.command.content.FindPagesByTemplateHandler;
import rs.slingshot.agent.command.content.FindPagesContainingPhraseHandler;
import rs.slingshot.agent.command.content.FindPagesUsingComponentsHandler;
import rs.slingshot.agent.command.content.ListAssetRenditionsHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.content.ListResourceMappingsHandler;
import rs.slingshot.agent.command.framework.FrameworkHandler;
import rs.slingshot.agent.command.job.JobHandler;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.ConfigurationCatalogues;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.PrincipalDirectories;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.command.platform.WorkflowService;
import rs.slingshot.agent.command.principal.PrincipalHandler;
import rs.slingshot.agent.command.replication.AgentHandler;
import rs.slingshot.agent.command.workflow.WorkflowHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/** An unauthorised continuation cannot start a legacy content or platform listing. */
final class LegacyPagingAdmissionTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final DiscoveryRegistry DISCOVERY = new DiscoveryRegistry(CONTRACT);

    @AfterAll
    static void releaseDiscovery() {
        DISCOVERY.close();
    }

    @ParameterizedTest
    @MethodSource("requests")
    void unavailableContinuationAuthorityPreventsEveryContentOrInventoryRead(CommandHandler handler,
                                                                             String arguments) {
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                handler.run(continuation(arguments), untouched(ResourceResolver.class), context()));
        assertEquals("continuation_token_integrity_invalid", refused.category());
    }

    private static Stream<Arguments> requests() {
        return Stream.concat(contentRequests(), platformRequests());
    }

    private static Stream<Arguments> contentRequests() {
        return Stream.of(
                Arguments.of(new FindAssetsByMetadataHandler(CONTRACT, DISCOVERY),
                        "{\"root_path\":\"/content\"}"),
                Arguments.of(new FindAssetsReferencedByPageHandler(CONTRACT),
                        "{\"page_path\":\"/content/page\"}"),
                Arguments.of(new FindPagesByTemplateHandler(CONTRACT),
                        "{\"root_path\":\"/content\",\"template_path\":\"/conf/template\"}"),
                Arguments.of(new FindPagesContainingPhraseHandler(CONTRACT, DISCOVERY),
                        "{\"phrase\":\"fixture\",\"root_path\":\"/content\"}"),
                Arguments.of(new FindPagesUsingComponentsHandler(CONTRACT, DISCOVERY),
                        "{\"match_mode\":\"any\",\"resource_types\":[\"fixture/type\"],"
                                + "\"root_path\":\"/content\"}"),
                Arguments.of(new ListAssetRenditionsHandler(CONTRACT),
                        "{\"asset_path\":\"/content/asset\"}"),
                Arguments.of(new ChildListingHandler(CONTRACT),
                        "{\"root_path\":\"/content\"}"),
                Arguments.of(new ChildListingHandler(CONTRACT),
                        "{\"primary_node_type\":\"cq:Page\",\"root_path\":\"/content\"}"),
                Arguments.of(new ListChildPagesHandler(CONTRACT),
                        "{\"root_path\":\"/content\"}"),
                Arguments.of(new ListResourceMappingsHandler(CONTRACT), "{}"),
                Arguments.of(new AuthoringCatalogHandler(CONTRACT,
                        AuthoringCatalogHandler.Kind.PAGE_TEMPLATES), "{\"root_path\":\"/conf\"}"),
                Arguments.of(new AuthoringCatalogHandler(CONTRACT,
                        AuthoringCatalogHandler.Kind.FRAGMENT_MODELS), "{\"root_path\":\"/conf\"}"));
    }

    private static Stream<Arguments> platformRequests() {
        final PlatformControl control = PlatformControl.of("fixture", Set.of());
        return Stream.of(
                Arguments.of(new JobHandler(CONTRACT, JobHandler.Kind.QUEUES,
                        untouched(JobInventory.class), control), "{}"),
                Arguments.of(new JobHandler(CONTRACT, JobHandler.Kind.JOBS,
                        untouched(JobInventory.class), control), "{\"states\":[\"queued\"]}"),
                Arguments.of(new FrameworkHandler(CONTRACT, FrameworkHandler.Kind.BUNDLES,
                        untouched(BundleInventory.class)), "{}"),
                Arguments.of(new FrameworkHandler(CONTRACT, FrameworkHandler.Kind.COMPONENTS,
                        untouched(BundleInventory.class)), "{}"),
                Arguments.of(new ConfigurationHandler(CONTRACT, ConfigurationHandler.Kind.SEARCH,
                        untouched(ConfigurationCatalogues.class)), "{}"),
                Arguments.of(new PrincipalHandler(CONTRACT, PrincipalHandler.Kind.LISTING,
                        untouched(PrincipalDirectories.class), control),
                        "{\"group_identifier\":\"fixture-group\",\"include_indirect\":false}"),
                Arguments.of(new AgentHandler(CONTRACT, AgentHandler.Kind.LISTING,
                        untouched(ReplicationInventory.class), control), "{}"),
                Arguments.of(new AgentHandler(CONTRACT, AgentHandler.Kind.QUEUE,
                        untouched(ReplicationInventory.class), control),
                        "{\"agent_identifier\":\"fixture-agent\"}"),
                Arguments.of(new WorkflowHandler(CONTRACT, WorkflowHandler.Kind.MODELS,
                        untouched(WorkflowService.class), control), "{}"),
                Arguments.of(new WorkflowHandler(CONTRACT, WorkflowHandler.Kind.INSTANCES,
                        untouched(WorkflowService.class), control), "{\"states\":[\"running\"]}"));
    }

    private static <Service> Service untouched(Class<Service> type) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type},
                (proxy, method, supplied) -> {
                    throw new AssertionError(
                            "an unauthorised continuation reached content or inventory access");
                }));
    }

    private static DocumentValue.Mapping continuation(String arguments) {
        final BoundedDocumentReader.Read read = assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(arguments.getBytes(StandardCharsets.UTF_8),
                        BoundedDocumentReader.Bounds.from(CONTRACT)));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>(
                assertInstanceOf(DocumentValue.Mapping.class, read.value()).members());
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.CONTINUATION_MODE));
        window.put(ResultWindow.TOKEN, new DocumentValue.Text("not-a-token"));
        members.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(window));
        return new DocumentValue.Mapping(members);
    }

    private static CallerContext context() {
        final AgentOperationIdentifier operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8", CONTRACT))
                .identifier();
        return new CallerContext(operation, Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }
}
