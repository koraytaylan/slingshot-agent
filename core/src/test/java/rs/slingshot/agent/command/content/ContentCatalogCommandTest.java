// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.fragment.FragmentHandlers;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What each of the four catalogues admits.
 */
@ExtendWith(SlingContextExtension.class)
final class ContentCatalogCommandTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load(), "the contract did not authenticate").contract();

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("an environment-shaped anchor lists every definition under /apps")
    void anEnvironmentShapedAnchorListsEveryDefinitionUnderApps() {
        sling.create().resource("/apps/acme/components/text", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        sling.create().resource("/apps/other/components/title", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        sling.create().resource("/libs/core/components/image", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, "/apps/acme-rde");
        assertEquals(List.of("/apps/acme/components/text", "/apps/other/components/title"),
                paths(answered));
    }

    @Test
    @DisplayName("a named project folder lists that project's definitions")
    void aNamedProjectFolderListsThatProjectsDefinitions() {
        sling.create().resource("/apps/acme/components/text", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        sling.create().resource("/apps/other/components/title", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, "/apps/acme");
        assertEquals(List.of("/apps/acme/components/text"), paths(answered));
    }

    @Test
    @DisplayName("a definition is the component node, and its type is the path under apps")
    void aDefinitionIsTheComponentNode() {
        sling.create().resource("/apps/example/components/text", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE,
                ListChildPagesHandler.TITLE_PROPERTY, "Text"));
        sling.create().resource("/apps/example/components/text/cq:dialog", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "nt:unstructured"));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, "/apps");
        assertEquals(List.of("/apps/example/components/text"), paths(answered));
        assertEquals("example/components/text", text(answered, ComponentListingResult.RESOURCE_TYPE));
    }

    @Test
    @DisplayName("an instance is a node that declares a resource type")
    void anInstanceDeclaresAResourceType() {
        sling.create().resource("/content/site/page/jcr:content/text", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "example/components/text"));
        sling.create().resource("/content/site/page/jcr:content/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        final DocumentValue.Mapping answered = listed(ContentCatalogHandler.Kind.COMPONENTS,
                "/content/site/page");
        assertEquals(List.of("/content/site/page/jcr:content/text"), paths(answered));
        assertEquals("example/components/text", text(answered, ComponentListingResult.RESOURCE_TYPE));
    }

    @Test
    @DisplayName("a content fragment is an asset carrying the fragment flag")
    void aContentFragmentCarriesTheFlag() {
        sling.create().resource("/content/dam/site/article", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/article/jcr:content", Map.of(
                FragmentHandlers.CONTENT_FRAGMENT_FLAG, true,
                ListChildPagesHandler.TITLE_PROPERTY, "Article"));
        sling.create().resource("/content/dam/site/photo", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/photo/jcr:content", Map.of(
                FragmentHandlers.CONTENT_FRAGMENT_FLAG, false));
        sling.create().resource("/content/dam/site/article/jcr:content/renditions/original");
        sling.create().resource("/content/dam/site/later", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/later/jcr:content", Map.of(
                FragmentHandlers.CONTENT_FRAGMENT_FLAG, true));
        final DocumentValue.Mapping answered = listed(ContentCatalogHandler.Kind.CONTENT_FRAGMENTS,
                "/content/dam");
        assertEquals(List.of("/content/dam/site/article", "/content/dam/site/later"),
                paths(answered));
    }

    @Test
    @DisplayName("an experience fragment is the page whose content is the fragment page")
    void anExperienceFragmentIsNotItsVariation() {
        final String fragment = "/content/experience-fragments/site/promo";
        sling.create().resource(fragment, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
        sling.create().resource(fragment + "/jcr:content", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY,
                FragmentHandlers.EXPERIENCE_FRAGMENT_RESOURCE_TYPE,
                ListChildPagesHandler.TITLE_PROPERTY, "Promo"));
        sling.create().resource(fragment + "/web", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
        sling.create().resource(fragment + "/web/jcr:content", Map.of(
                "cq:template", "/conf/site/settings/wcm/templates/xf"));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.EXPERIENCE_FRAGMENTS, "/content/experience-fragments");
        assertEquals(List.of(fragment), paths(answered));
    }

    @Test
    @DisplayName("an argument this catalogue does not take is refused before any node is read")
    void anArgumentThisCatalogueDoesNotTakeIsRefused() {
        for (final ContentCatalogHandler.Kind kind : ContentCatalogHandler.Kind.values()) {
            final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                    new ContentCatalogHandler(CONTRACT, kind).run(stray(), readOnly(), context()));
            assertEquals(ContentCatalogHandler.ARGUMENT_REJECTED, failed.category());
        }
        final List<String> expected = List.of("NOT_A_DOCUMENT", "MEMBER_UNKNOWN", "MEMBER_ABSENT",
                "NOT_AN_ABSOLUTE_PATH", "NOT_AN_ABSOLUTE_PATH", "WINDOW_REFUSED");
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ListComponentDefinitionsCommand.Refused.class,
                        ListComponentDefinitionsCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ComponentInstancesCommand.Refused.class,
                        ComponentInstancesCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ListContentFragmentsCommand.Refused.class,
                        ListContentFragmentsCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ListExperienceFragmentsCommand.Refused.class,
                        ListExperienceFragmentsCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
    }

    @Test
    @DisplayName("an anchor nothing is at is refused rather than answered empty")
    void anAnchorNothingIsAtIsRefused() {
        final CommandHandler.Answer answer = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS)
                .run(arguments("/content/missing"), readOnly(), context());
        assertInstanceOf(CommandHandler.Failed.class, answer,
                "a missing anchor was answered, which reads as there being no components");
    }

    @Test
    @DisplayName("a walk that reaches its node budget exactly answers, and one node more is refused")
    void aWalkPastItsNodeBudgetIsRefusedRatherThanAnsweredInPart() {
        sling.create().resource("/content/site/first", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/components/text"));
        sling.create().resource("/content/site/second", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/components/title"));
        final ContentCatalogHandler handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS);
        final int everyNode = 3;
        assertInstanceOf(CommandHandler.Produced.class,
                handler.run(arguments("/content/site"), readOnly(), budgeted(everyNode)));
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                handler.run(arguments("/content/site"), readOnly(), budgeted(everyNode - 1)));
        assertEquals(ContentCatalogHandler.DISCOVERY_BUDGET_EXCEEDED, failed.category());
    }

    private static CallerContext budgeted(long nodes) {
        return new CallerContext(operation(), new Budget(Budget.Kind.DISCOVERY, nodes),
                Budget.time(CONTRACT), new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT),
                new CallerContext.Available(authority(), target(), generation(), 1_000L));
    }

    private DocumentValue.Mapping listed(ContentCatalogHandler.Kind kind, String root) {
        final CommandHandler.Answer answer = new ContentCatalogHandler(CONTRACT, kind)
                .run(arguments(root), readOnly(), context());
        assertInstanceOf(CommandHandler.Produced.class, answer, answer.toString());
        return ((CommandHandler.Produced) answer).result();
    }

    private static List<String> paths(DocumentValue.Mapping answered) {
        return ((DocumentValue.Sequence) answered.member(PageListingResult.MATCHES).orElseThrow())
                .items().stream()
                .map(item -> ((DocumentValue.Text) ((DocumentValue.Mapping) item)
                        .member(PageListingResult.REPOSITORY_PATH).orElseThrow()).value())
                .toList();
    }

    private static String text(DocumentValue.Mapping answered, String member) {
        final DocumentValue.Mapping row = (DocumentValue.Mapping)
                ((DocumentValue.Sequence) answered.member(PageListingResult.MATCHES).orElseThrow())
                        .items().getFirst();
        return ((DocumentValue.Text) row.member(member).orElseThrow()).value();
    }

    private static DocumentValue.Mapping stray() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put("other", new DocumentValue.Text("x"));
        return new DocumentValue.Mapping(members);
    }

    private static List<DocumentValue> refusedArguments() {
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("other", new DocumentValue.Text("x"));
        final SequencedMap<String, DocumentValue> untyped = new LinkedHashMap<>();
        untyped.put(RootedWindow.ROOT_PATH, new DocumentValue.Whole(1));
        final SequencedMap<String, DocumentValue> relative = new LinkedHashMap<>();
        relative.put(RootedWindow.ROOT_PATH, new DocumentValue.Text("content"));
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(RootedWindow.ROOT_PATH, new DocumentValue.Text("/content"));
        final SequencedMap<String, DocumentValue> mode = new LinkedHashMap<>();
        mode.put(ResultWindow.MODE, new DocumentValue.Text("sideways"));
        window.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(mode));
        return List.of(new DocumentValue.Text("no"), new DocumentValue.Mapping(unknown),
                new DocumentValue.Mapping(new LinkedHashMap<>()),
                new DocumentValue.Mapping(untyped), new DocumentValue.Mapping(relative),
                new DocumentValue.Mapping(window));
    }

    private static DocumentValue.Mapping arguments(String root) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(RootedWindow.ROOT_PATH, new DocumentValue.Text(root));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT),
                new CallerContext.Available(authority(), target(), generation(), 1_000L));
    }

    private static ContinuationKeyAuthority authority() {
        return new ContinuationKeyAuthority() {
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
    }

    private static DigestValue target() {
        return assertInstanceOf(DigestValue.Held.class,
                DigestValue.of("b".repeat(DigestValue.RENDERED_LENGTH))).digest();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST)).generation();
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }
}
