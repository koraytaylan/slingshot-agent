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
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The two directions of resource mapping, and the round trip between them.
 *
 * <p>They are tested together because they are one subject: an operator chasing a wrong link asks
 * both questions about the same address within a minute of each other, and proving them apart would
 * leave the thing they actually care about — that one undoes the other — unproved.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ResourceResolutionCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** A request every resolution in this suite happens under. */
    private static final String REQUEST = "https://example.test/";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("an address survives a round trip through both directions")
    void anaddressSurvivesTheRoundTrip() {
        sling.create().resource("/content/site/page", Map.of());
        final String mapped = ((DocumentValue.Text) mapped("/content/site/page")
                .member(MapResourcePathResult.MAPPED_ADDRESS).orElseThrow()).value();
        final String back = ((DocumentValue.Text) resolved(mapped)
                .member(ResolveResourcePathResult.RESOLVED_PATH).orElseThrow()).value();
        assertEquals("/content/site/page", back,
                "mapping an address and resolving the result did not return the original, so one"
                        + " direction does not undo the other and a link built from the mapped form"
                        + " points somewhere else");
    }

    @Test
    @DisplayName("the two commands take different arguments, and neither accepts the other's")
    void thetwoArgumentsAreNotOneArgument() {
        // Resolving happens under a request and mapping does not, so the client publishes two
        // argument schemas rather than one with a direction. Each command refuses the other's own
        // member: a caller who sent the wrong one believes something untrue about the answer.
        assertEquals(ResolveResourcePathCommand.Refusal.MEMBER_UNKNOWN,
                assertInstanceOf(ResolveResourcePathCommand.Refused.class,
                        ResolveResourcePathCommand.of(mapArgument("/content/site/page", null),
                                CONTRACT),
                        "the resolving command accepted a repository path").refusal());
        assertEquals(MapResourcePathCommand.Refusal.MEMBER_UNKNOWN,
                assertInstanceOf(MapResourcePathCommand.Refused.class,
                        MapResourcePathCommand.of(resolveArgument(REQUEST), CONTRACT),
                        "the mapping command accepted a request address").refusal());
        assertEquals(MapResourcePathCommand.Refusal.MEMBER_ABSENT,
                assertInstanceOf(MapResourcePathCommand.Refused.class,
                        MapResourcePathCommand.of(new DocumentValue.Mapping(new LinkedHashMap<>()),
                                CONTRACT),
                        "an argument naming nothing was accepted").refusal());
    }

    @Test
    @DisplayName("a request address the rules will not accept is its own refusal")
    void arejectedRequestIsItsOwnRefusal() {
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_ADDRESS_BYTES);
        final ResolveResourcePathCommand.Refused refused =
                assertInstanceOf(ResolveResourcePathCommand.Refused.class,
                        ResolveResourcePathCommand.of(
                                resolveArgument("h".repeat((int) bound + 1)), CONTRACT),
                        "an address past the bound was accepted");
        assertEquals(ResolveResourcePathCommand.Refusal.REQUEST_ADDRESS_REJECTED,
                refused.refusal());
        assertEquals(ResolveResourcePathHandler.REQUEST_ADDRESS_REJECTED,
                ResolveResourcePathHandler.categoryFor(refused.refusal()),
                "a malformed question was reported as a correct answer of no, and those are"
                        + " different things a caller does different things about");
        assertInstanceOf(ResolveResourcePathCommand.Held.class,
                ResolveResourcePathCommand.of(resolveArgument("h".repeat((int) bound)), CONTRACT),
                "an address exactly at the bound was refused");
    }

    @Test
    @DisplayName("a trace travels back only where it was asked for")
    void atraceIsAskedForRatherThanSent() {
        sling.create().resource("/content/site/page", Map.of());
        assertTrue(mapped("/content/site/page").member(MapResourcePathResult.TRACE).isEmpty(),
                "a caller who asked for no trace was sent one anyway, on every request");
        final DocumentValue.Mapping traced = assertInstanceOf(CommandHandler.Produced.class,
                new MapResourcePathHandler(CONTRACT).run(
                        mapArgument("/content/site/page", null, TraceDisclosure.INCLUDED),
                        readOnly(), context()),
                "the mapping was refused").result();
        // A mapping that changed nothing has nothing to trace, and saying so is the useful answer:
        // an operator chasing a link that was not rewritten needs to know no rule fired, which is
        // a different thing from a rule firing and producing the same text.
        assertTrue(traced.member(MapResourcePathResult.TRACE).isEmpty(),
                "an address nothing rewrote was reported as having gone through a rule");
    }

    @Test
    @DisplayName("the parts of a request the platform did not resolve with are taken apart")
    void thepartsOfTheRequestAreReported() {
        // Proved on the record Sling itself keeps rather than through a resolver, because that
        // record is the input this side actually reads and a mock resolver does not produce one.
        // A selector nobody expected is the usual reason one link renders and another answers a
        // 404, so this is the part worth proving exactly.
        final RequestParts parts = RequestParts.of(".print.html/a/b");
        assertEquals(List.of("print"), parts.selectors());
        assertEquals("html", parts.extension());
        assertEquals("/a/b", parts.suffix());
        final RequestParts plain = RequestParts.of("");
        assertEquals(List.of(), plain.selectors(),
                "an address the platform resolved whole was reported as carrying selectors");
        assertEquals(ResolveResourcePathResult.ABSENT, plain.extension(),
                "a request that named no extension was answered with an empty one, which reads as"
                        + " an extension that is the empty string");
        assertEquals(List.of("a", "b"), RequestParts.of(".a.b.html").selectors(),
                "a request carrying several selectors lost all but one");
        sling.create().resource("/content/site/page", Map.of());
        final DocumentValue.Mapping answered = resolved("/content/site/page");
        assertEquals(new DocumentValue.Text("/content/site/page"),
                answered.member(ResolveResourcePathResult.REQUEST_ADDRESS).orElseThrow(),
                "the answer does not echo the address it is about");
        assertTrue(answered.member(ResolveResourcePathResult.SELECTORS).isPresent(),
                "the selectors are required by the client's own schema and are not there");
    }

    @Test
    @DisplayName("both rows are the client's own and differ by exactly the request address")
    void thetworowsDifferByOneThing() {
        final RegistryRow resolve = row(ResolveResourcePathCommand.WIRE_NAME);
        final RegistryRow map = row(MapResourcePathCommand.WIRE_NAME);
        assertEquals(RegistryRow.OperationKey.REFUSED, resolve.operationKey());
        assertEquals(RegistryRow.OperationKey.REFUSED, map.operationKey());
        assertEquals(262144, resolve.resultBytes());
        assertEquals(262144, map.resultBytes());
        assertEquals(resolve.failureCategories().stream().sorted().toList(),
                new ResolveResourcePathHandler(CONTRACT).categories().stream().sorted().toList());
        assertEquals(map.failureCategories().stream().sorted().toList(),
                new MapResourcePathHandler(CONTRACT).categories().stream().sorted().toList());
        assertEquals(1, resolve.failureCategories().size() - map.failureCategories().size(),
                "the two directions no longer differ by exactly the one failure only one of them"
                        + " can produce");
    }

    @Test
    @DisplayName("an argument that is not one either command takes is refused as an argument")
    void ablankAddressIsAMalformedQuestion() {
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new MapResourcePathHandler(CONTRACT).run(mapArgument("   ", null), readOnly(),
                        context()),
                "a blank address was translated");
        assertEquals(MapResourcePathHandler.ARGUMENT_REJECTED, failed.category(),
                "a blank address is a malformed question rather than an answer of no");
    }

    @Test
    @DisplayName("a trace asked for arrives where something changed, and the parts are answered")
    void atraceArrivesWhereSomethingChanged() {
        sling.create().resource("/content/site/page", Map.of());
        final DocumentValue.Mapping traced = assertInstanceOf(CommandHandler.Produced.class,
                new ResolveResourcePathHandler(CONTRACT).run(tracedResolve("/content/site/page"),
                        readOnly(), context()),
                "the resolution was refused").result();
        // Nothing rewrote this address, so there is nothing to trace and saying so is the useful
        // answer: an operator chasing a link that was not rewritten needs to know no rule fired.
        assertTrue(traced.member(ResolveResourcePathResult.TRACE).isEmpty(),
                "an address nothing rewrote was reported as having gone through a rule");
        assertEquals(new DocumentValue.Text("/content/site/page"),
                traced.member(ResolveResourcePathResult.RESOLVED_PATH).orElseThrow());
        final DocumentValue.Mapping rendered = ResolveResourcePathResult.documentOf(
                new ResolveResourcePathResult.Resolution("https://example.test/site.print.html",
                        "/content/site", "site/components/page", List.of("print"), "html",
                        "/a/b", List.of("/content/site")));
        assertEquals(new DocumentValue.Text("site/components/page"),
                rendered.member(ResolveResourcePathResult.RESOURCE_TYPE).orElseThrow());
        assertEquals(new DocumentValue.Text("html"),
                rendered.member(ResolveResourcePathResult.EXTENSION).orElseThrow());
        assertEquals(new DocumentValue.Text("/a/b"),
                rendered.member(ResolveResourcePathResult.SUFFIX).orElseThrow());
        assertEquals(1, ((DocumentValue.Sequence) rendered
                        .member(ResolveResourcePathResult.TRACE).orElseThrow()).items().size(),
                "a trace that was gathered did not reach the caller");
        final DocumentValue.Mapping bare = ResolveResourcePathResult.documentOf(
                new ResolveResourcePathResult.Resolution("https://example.test/site",
                        ResolveResourcePathResult.ABSENT, ResolveResourcePathResult.ABSENT,
                        List.of(), ResolveResourcePathResult.ABSENT,
                        ResolveResourcePathResult.ABSENT, List.of()));
        assertTrue(bare.member(ResolveResourcePathResult.EXTENSION).isEmpty(),
                "a request that named no extension was answered with an empty one, which reads as"
                        + " an extension that is the empty string");
    }

    @Test
    @DisplayName("a mapping that went through a rule reports the path it started from")
    void amappingReportsWhereItStarted() {
        final DocumentValue.Mapping rendered = MapResourcePathResult.documentOf(
                "/content/site/page", "https://example.test/page.html",
                List.of("/content/site/page"));
        assertEquals(new DocumentValue.Text("https://example.test/page.html"),
                rendered.member(MapResourcePathResult.MAPPED_ADDRESS).orElseThrow());
        assertEquals(1, ((DocumentValue.Sequence) rendered.member(MapResourcePathResult.TRACE)
                        .orElseThrow()).items().size(),
                "a mapping that rewrote an address reported no rule at all");
    }

    private static DocumentValue.Mapping tracedResolve(String address) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResolveResourcePathCommand.REQUEST_ADDRESS, new DocumentValue.Text(address));
        members.put(TraceDisclosure.ARGUMENT_MEMBER,
                new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        return new DocumentValue.Mapping(members);
    }

    private DocumentValue.Mapping mapped(String path) {
        return assertInstanceOf(CommandHandler.Produced.class,
                new MapResourcePathHandler(CONTRACT).run(mapArgument(path, null), readOnly(),
                        context()),
                "the mapping was refused").result();
    }

    private DocumentValue.Mapping resolved(String address) {
        return assertInstanceOf(CommandHandler.Produced.class,
                new ResolveResourcePathHandler(CONTRACT).run(resolveArgument(address), readOnly(),
                        context()),
                "the resolution was refused").result();
    }

    private static DocumentValue.Mapping resolveArgument(String address) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResolveResourcePathCommand.REQUEST_ADDRESS, new DocumentValue.Text(address));
        members.put(TraceDisclosure.ARGUMENT_MEMBER, new DocumentValue.Flag(
                DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping mapArgument(String path, String authority) {
        return mapArgument(path, authority, TraceDisclosure.OMITTED);
    }

    private static DocumentValue.Mapping mapArgument(String path, String authority,
                                                     TraceDisclosure trace) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(MapResourcePathCommand.REPOSITORY_PATH, new DocumentValue.Text(path));
        if (authority != null) {
            members.put(MapResourcePathCommand.REQUEST_AUTHORITY,
                    new DocumentValue.Text(authority));
        }
        members.put(TraceDisclosure.ARGUMENT_MEMBER, new DocumentValue.Flag(
                trace == TraceDisclosure.INCLUDED
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_OPERATIONAL_INSPECTION_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row(String wireName) {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry().row(wireName).orElseThrow();
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
