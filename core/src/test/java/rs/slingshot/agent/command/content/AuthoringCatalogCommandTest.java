// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import rs.slingshot.agent.command.page.CreatePageHandler;
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
 * The two catalogues {@code create_page} and {@code create_content_fragment} are pointed at.
 */
@ExtendWith(SlingContextExtension.class)
final class AuthoringCatalogCommandTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load(), "the contract did not authenticate").contract();

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a template is the node inside settings/wcm/templates, and nothing under it")
    void atemplateIsTheNodeInsideTheTemplatesFolder() {
        final String template = "/conf/site/settings/wcm/templates/article";
        sling.create().resource(template, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
        sling.create().resource(template + "/jcr:content", Map.of(
                ListChildPagesHandler.TITLE_PROPERTY, "Article"));
        sling.create().resource("/conf/site/settings/wcm/templates/article/structure", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
        sling.create().resource("/conf/site/settings/wcm/template-types/page", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
        final DocumentValue.Mapping answered = assertInstanceOf(DocumentValue.Mapping.class,
                listed(AuthoringCatalogHandler.Kind.PAGE_TEMPLATES, "/conf/site"));
        assertEquals(List.of(template), paths(answered),
                "the structure under a template and a template type were listed as templates");
    }

    @Test
    @DisplayName("a model is the node inside settings/dam/cfm/models that declares elements")
    void amodelIsTheNodeInsideTheModelsFolder() {
        final String model = "/conf/site/settings/dam/cfm/models/article";
        sling.create().resource(model, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
        sling.create().resource(model + "/jcr:content/model/cq:dialog/content/items/title",
                Map.of(ListChildPagesHandler.TYPE_PROPERTY, "nt:unstructured"));
        sling.create().resource("/conf/site/settings/dam/cfm/models/empty", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, CreatePageHandler.TEMPLATE_TYPE));
        final DocumentValue.Mapping answered = assertInstanceOf(DocumentValue.Mapping.class,
                listed(AuthoringCatalogHandler.Kind.FRAGMENT_MODELS, "/conf"));
        assertEquals(List.of(model), paths(answered),
                "a node in the models folder that declares no elements was listed as a model");
    }

    @Test
    @DisplayName("an anchor nothing is at is refused rather than answered empty")
    void anAnchorNothingIsAtIsRefused() {
        final CommandHandler.Answer answer = new AuthoringCatalogHandler(CONTRACT,
                AuthoringCatalogHandler.Kind.PAGE_TEMPLATES)
                .run(arguments("/conf/missing"), readOnly(), context());
        assertInstanceOf(CommandHandler.Failed.class, answer,
                "a missing anchor was answered, which reads as there being no templates");
    }

    private DocumentValue.Mapping listed(AuthoringCatalogHandler.Kind kind, String root) {
        final CommandHandler.Answer answer = new AuthoringCatalogHandler(CONTRACT, kind)
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

    @Test
    @DisplayName("the folder rule agrees with the path a caller would hand to create")
    void theFolderRuleAgrees() {
        assertTrue(AuthoringCatalogHandler.directChildOf(
                "/conf/site/settings/wcm/templates/article",
                List.of("settings", "wcm", "templates")));
        assertFalse(AuthoringCatalogHandler.directChildOf(
                "/conf/site/settings/wcm/templates/article/jcr:content",
                List.of("settings", "wcm", "templates")));
    }
}
