// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The resolution rules, in the platform's own order, with no credential in any of them.
 *
 * <p>The credential removal is the claim this suite exists for. A mapping address can carry one,
 * and it is taken out rather than masked: a mask still says a credential was there and how long it
 * was, and the rule an operator came to read is the same rule without it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ListResourceMappingsCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** A credential in a mapping address, which must appear nowhere in any answer. */
    private static final String CREDENTIAL = "publisher:a-password-nobody-should-receive";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a credential in a mapping address is removed rather than masked")
    void acredentialIsRemovedRatherThanMasked() {
        inventory();
        final DocumentValue.Mapping listed = listed();
        final String rendered = String.valueOf(listed);
        assertTrue(!rendered.contains(CREDENTIAL),
                "a credential from a mapping address reached the caller: " + rendered);
        assertTrue(!rendered.contains("***") && !rendered.contains("REDACTED"),
                "the credential was masked rather than removed. A mask still says one was there"
                        + " and how long it was, and the rule an operator came to read is the same"
                        + " rule without it: " + rendered);
        assertTrue(replacementsFrom(listed).contains("https://publish.example.test/"),
                "the address survived without its credential, which is the rule itself: "
                        + replacementsFrom(listed));
    }

    @Test
    @DisplayName("every shape of credential is taken out, and an address without one is untouched")
    void everyshapeOfCredentialIsTakenOut() {
        assertEquals("https://host.example.test/path", ListResourceMappingsResult
                .withoutCredentials("https://user:secret@host.example.test/path"));
        assertEquals("https://host.example.test/path", ListResourceMappingsResult
                .withoutCredentials("https://user@host.example.test/path"));
        assertEquals("https://host.example.test/path", ListResourceMappingsResult
                .withoutCredentials("https://host.example.test/path"),
                "an address with no credential was changed, and the rule an operator reads is no"
                        + " longer the rule the platform holds");
        assertEquals("/content/site", ListResourceMappingsResult.withoutCredentials("/content/site"),
                "a repository path was treated as though it had an authority");
        assertEquals("https://host.example.test/a@b", ListResourceMappingsResult
                .withoutCredentials("https://host.example.test/a@b"),
                "an at sign in the path was read as a credential separator, so part of the rule"
                        + " was cut off");
    }

    @Test
    @DisplayName("the entries are in the platform's own application order, not declaration order")
    void theentriesAreInApplicationOrder() {
        inventory();
        assertEquals(List.of("localhost.8080", "publish", "author"), patternsFrom(listed()),
                "the entries are not in the order the repository holds them. Somebody reading this"
                        + " has an address that resolved unexpectedly and wants to know which rule"
                        + " got to it first; another order would lead them to the wrong rule.");
    }

    @Test
    @DisplayName("an inventory that cannot be read is refused rather than answered as empty")
    void anunreadableInventoryIsRefused() {
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new ListResourceMappingsHandler(CONTRACT)
                        .run(argument(25), readOnly(), context()),
                "an inventory that is not there was answered with a list");
        assertEquals(ListResourceMappingsHandler.INVENTORY_FAILED, failed.category());
        assertTrue(failed.detail().contains("no reason to doubt"), failed.detail());
    }

    @Test
    @DisplayName("a filter is refused rather than ignored, because rules interact")
    void afilterIsRefused() {
        final SequencedMap<String, DocumentValue> members =
                new LinkedHashMap<>(argument(25).members());
        members.put("pattern", new DocumentValue.Text("^/content"));
        assertEquals(ListResourceMappingsCommand.Refusal.MEMBER_UNKNOWN,
                refusalOf(new DocumentValue.Mapping(members)).refusal(),
                "a filter was ignored rather than refused, and a caller who sent one would believe"
                        + " they had read a filtered view of rules that interact");
        assertEquals(ListResourceMappingsCommand.Refusal.MEMBER_ABSENT,
                refusalOf(new DocumentValue.Mapping(new LinkedHashMap<>())).refusal());
        assertEquals(ListResourceMappingsCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument(0)).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and declares its own inventory failure")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new ListResourceMappingsHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
        assertTrue(row.failureCategories().contains(ListResourceMappingsHandler.INVENTORY_FAILED),
                "this command no longer declares the failure that keeps an unreadable inventory"
                        + " from being answered as a deployment with no rules");
    }

    private DocumentValue.Mapping listed() {
        return assertInstanceOf(CommandHandler.Produced.class,
                new ListResourceMappingsHandler(CONTRACT).run(argument(100), readOnly(), context()),
                "the listing was refused").result();
    }

    private static List<String> patternsFrom(DocumentValue.Mapping result) {
        return entriesIn(result).stream()
                .map(entry -> ((DocumentValue.Text) ((DocumentValue.Mapping) entry)
                        .member(ListResourceMappingsResult.PATTERN).orElseThrow()).value())
                .toList();
    }

    @Test
    @DisplayName("each kind of rule names itself back, and a redirect carries the status it answers")
    void eachkindNamesItselfBack() {
        for (final MappingKind kind : MappingKind.values()) {
            assertEquals(kind, MappingKind.named(kind.spelling()).orElseThrow(),
                    kind + " does not name itself back from its own spelling");
        }
        assertEquals(java.util.Optional.empty(), MappingKind.named("rewrite"),
                "a kind the platform does not have was read as one");
        final DocumentValue.Mapping rendered = ListResourceMappingsResult.documentOf(
                List.of(new ListResourceMappingsResult.MappingEntry("/etc/map/http/example",
                        MappingKind.REDIRECT, "^/old", List.of("/new", "/newer"), MOVED)),
                "a-token");
        final DocumentValue.Mapping entry = (DocumentValue.Mapping) ((DocumentValue.Sequence)
                rendered.member(ListResourceMappingsResult.ENTRIES).orElseThrow()).items()
                .getFirst();
        assertEquals(new DocumentValue.Whole(MOVED),
                entry.member(ListResourceMappingsResult.STATUS_CODE).orElseThrow(),
                "a redirecting rule does not say what status it answers with");
        assertEquals(2, ((DocumentValue.Sequence) entry
                        .member(ListResourceMappingsResult.REPLACEMENTS).orElseThrow())
                        .items().size(),
                "a rule listing two alternatives reported fewer, so an operator cannot see what"
                        + " else it would have tried");
        assertEquals(new DocumentValue.Text("a-token"),
                rendered.member(ListResourceMappingsResult.NEXT_CONTINUATION_TOKEN).orElseThrow());
        assertTrue(ListResourceMappingsResult.documentOf(List.of(
                        new ListResourceMappingsResult.MappingEntry("/etc/map/http/example",
                                MappingKind.MAP, "^/old", List.of("/new"),
                                ListResourceMappingsResult.NO_STATUS)), "")
                        .member(ListResourceMappingsResult.NEXT_CONTINUATION_TOKEN).isEmpty(),
                "a page at the end carried a token member");
    }

    /** The status a moved-permanently rule answers with. */
    private static final long MOVED = 301;

    private static List<String> replacementsFrom(DocumentValue.Mapping result) {
        return entriesIn(result).stream()
                .flatMap(entry -> ((DocumentValue.Sequence) ((DocumentValue.Mapping) entry)
                        .member(ListResourceMappingsResult.REPLACEMENTS).orElseThrow())
                        .items().stream())
                .map(replacement -> ((DocumentValue.Text) replacement).value())
                .toList();
    }

    private static List<DocumentValue> entriesIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(ListResourceMappingsResult.ENTRIES)
                .orElseThrow()).items();
    }

    private void inventory() {
        sling.create().resource(ListResourceMappingsHandler.MAPPING_ROOT, Map.of());
        entry("localhost.8080", "/content/site");
        entry("publish", "https://" + CREDENTIAL + "@publish.example.test/");
        entry("author", "https://author.example.test/");
    }

    private void entry(String name, String replacement) {
        sling.create().resource(ListResourceMappingsHandler.MAPPING_ROOT + "/" + name, Map.of(
                ListResourceMappingsHandler.REDIRECT_PROPERTY, replacement,
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Mapping"));
    }

    private static ListResourceMappingsCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(ListResourceMappingsCommand.Refused.class,
                ListResourceMappingsCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        window.put(ResultWindow.OFFSET, new DocumentValue.Whole(0));
        window.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        members.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(window));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row() {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry()
                .row(ListResourceMappingsCommand.WIRE_NAME).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
