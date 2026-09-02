// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.QueryDigest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.CommandFailure;

/**
 * Paging to a definite end, and a token that belongs to exactly one query.
 *
 * <p>The two things worth proving here are that an absent token means the end rather than an
 * unknown, and that a token cannot be carried from one query to another - because the second
 * failure, if it were not caught, would answer a caller with rows that look perfectly reasonable
 * and belong to a different question.</p>
 */
final class PagedQueryTest {

    private static final AgentContract CONTRACT = contract();

    /** A key this test signs with, which is this test's own and reaches nothing. */
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    /** What this side's clock says, fixed so an expiry is arithmetic rather than a race. */
    private static final long NOW = 1_700_000_000_000L;

    @Test
    @DisplayName("a page carrying every remaining row has no token, so the end is definite")
    void theEndIsDefinite() {
        final PagedQuery.Page<String> whole = PagedQuery.pageOf(rows(3), 10, 0);
        assertEquals(List.of("row-0", "row-1", "row-2"), whole.rows());
        assertInstanceOf(PagedQuery.Nothing.class, whole.following(),
                "a page that carried everything claimed something followed it");
        assertEquals(Optional.empty(),
                query().tokenFor(whole, theQuery(), ring(), NOW, CONTRACT),
                "a page with nothing following it was given a token to reach it by");
    }

    @Test
    @DisplayName("a page with rows left over serves the window and issues a token past it")
    void aPageWithRowsLeftOverIssuesAToken() {
        final PagedQuery.Page<String> first = PagedQuery.pageOf(rows(4), 3, 0);
        assertEquals(List.of("row-0", "row-1", "row-2"), first.rows(),
                "the row that proved more exist was served to the caller");
        assertEquals(new PagedQuery.More(3), first.following());
        assertTrue(query().tokenFor(first, theQuery(), ring(), NOW, CONTRACT).isPresent());
    }

    @Test
    @DisplayName("paging walks to the end and stops, having served every row exactly once")
    void pagingWalksToTheEnd() {
        final List<String> everything = rows(7);
        final List<String> served = new ArrayList<>();
        PagedQuery.Following following = new PagedQuery.More(0);
        while (following instanceof final PagedQuery.More more) {
            final int at = (int) more.at();
            final PagedQuery.Page<String> page = PagedQuery.pageOf(
                    everything.subList(at, Math.min(everything.size(), at + PAGE + 1)), PAGE, at);
            served.addAll(page.rows());
            following = page.following();
        }
        assertEquals(everything, served, "paging did not serve every row exactly once");
    }

    /** How many rows a page carries while this suite walks one enumeration to its end. */
    private static final int PAGE = 2;

    @Test
    @DisplayName("a window of zero is refused and one above the maximum is refused as its own thing")
    void theTwoWindowRefusalsAreDistinct() {
        assertEquals(ResultWindow.Refusal.LIMIT_ZERO, windowRefusal(0, 0),
                "a page of no rows answers no question anybody meant to ask");
        assertEquals(ResultWindow.Refusal.LIMIT_ABOVE_MAXIMUM,
                windowRefusal(0, CONTRACT.value(ContractLimit.MAXIMUM_RESULT_LIMIT) + 1),
                "a window above the contract's maximum was not refused as such");
        assertEquals(ResultWindow.Refusal.OFFSET_ABOVE_MAXIMUM,
                windowRefusal(CONTRACT.value(ContractLimit.MAXIMUM_RESULT_OFFSET) + 1, 10));
        assertInstanceOf(ResultWindow.Held.class,
                ResultWindow.initial(0, CONTRACT.value(ContractLimit.MAXIMUM_RESULT_LIMIT),
                        CONTRACT),
                "the maximum itself was refused, so the bound is exclusive where it is inclusive");
    }

    @Test
    @DisplayName("a continuation window carries a token alone, and its shapes refuse distinctly")
    void aContinuationCarriesATokenAlone() {
        assertInstanceOf(ResultWindow.Held.class, ResultWindow.continuation("a-token", CONTRACT));
        assertEquals(ResultWindow.Refusal.TOKEN_EMPTY, continuationRefusal(""));
        assertEquals(ResultWindow.Refusal.TOKEN_CONTROL_CHARACTER,
                continuationRefusal("a\tb"));
        assertEquals(ResultWindow.Refusal.TOKEN_TOO_LONG, continuationRefusal("t".repeat(
                (int) CONTRACT.value(ContractLimit.MAXIMUM_CONTINUATION_TOKEN_BYTES) + 1)));
    }

    @Test
    @DisplayName("the digest covers every argument but the window, one argument at a time")
    void theDigestIsSensitiveToEveryArgumentButTheWindow() {
        final DigestValue base = digestOf(arguments("/content", "cq:Page", 10));
        assertNotEquals(base, digestOf(arguments("/content/other", "cq:Page", 10)),
                "the digest ignored the root, so two different questions share a position");
        assertNotEquals(base, digestOf(arguments("/content", "dam:Asset", 10)),
                "the digest ignored the type, so two different questions share a position");
        assertEquals(base, digestOf(arguments("/content", "cq:Page", 25)),
                "the digest covered the window, so no token is ever valid for its own successor");
    }

    @Test
    @DisplayName("a token from one query is refused on another as belonging to another query")
    void aTokenCannotBeCarriedToAnotherQuery() {
        final PagedQuery.Page<String> page = PagedQuery.pageOf(rows(4), 3, 0);
        final ContinuationToken token =
                query().tokenFor(page, theQuery(), ring(), NOW, CONTRACT).orElseThrow();
        final ContinuationToken.Outcome carried =
                token.validate(ring(), target(), anotherQuery(), serving(), NOW, CONTRACT);
        assertEquals(ContinuationToken.Refusal.WRONG_QUERY,
                assertInstanceOf(ContinuationToken.Refused.class, carried,
                        "a token for another query was honoured").refusal(),
                "the token was refused as damaged rather than as another query's");
        assertInstanceOf(ContinuationToken.Honoured.class,
                token.validate(ring(), target(), theQuery(), serving(), NOW, CONTRACT),
                "the token was not honoured on the query it came from");
    }

    @Test
    @DisplayName("a token resumes where the served page ended, so no row is served twice")
    void aTokenResumesWhereThePageEnded() {
        final PagedQuery.Page<String> page = PagedQuery.pageOf(rows(9), 4, 0);
        final ContinuationToken token =
                query().tokenFor(page, theQuery(), ring(), NOW, CONTRACT).orElseThrow();
        assertEquals(4, token.unvalidatedState().position(),
                "the token resumes somewhere other than where the served page ended");
    }

    @Test
    @DisplayName("each of the six continuation refusals reports its own category and detail")
    void eachRefusalReportsItsOwnCategory() {
        assertEquals(CommandFailure.Category.ARGUMENT_REJECTED,
                ContinuationRefusal.categoryOf(ContinuationToken.Refusal.MALFORMED));
        assertEquals(CommandFailure.Category.ARGUMENT_REJECTED,
                ContinuationRefusal.categoryOf(ContinuationToken.Refusal.INTEGRITY_INVALID));
        assertEquals(CommandFailure.Category.CONFLICT,
                ContinuationRefusal.categoryOf(ContinuationToken.Refusal.WRONG_TARGET));
        assertEquals(CommandFailure.Category.CONFLICT,
                ContinuationRefusal.categoryOf(ContinuationToken.Refusal.WRONG_QUERY));
        assertEquals(CommandFailure.Category.NOT_FOUND,
                ContinuationRefusal.categoryOf(ContinuationToken.Refusal.WRONG_GENERATION));
        assertEquals(CommandFailure.Category.NOT_FOUND,
                ContinuationRefusal.categoryOf(ContinuationToken.Refusal.EXPIRED));
        final List<String> details = List.of(ContinuationToken.Refusal.values()).stream()
                .map(ContinuationRefusal::detailOf)
                .toList();
        assertEquals(details.size(), details.stream().distinct().count(),
                "two refusals are told to a caller in the same words: " + details);
        assertTrue(details.stream().noneMatch(String::isBlank), "a refusal says nothing");
    }

    @Test
    @DisplayName("every refusal the token declares is mapped, so no seventh reaches a fallback")
    void everyRefusalIsMapped() {
        final List<CommandFailure.Category> mapped =
                List.of(ContinuationToken.Refusal.values()).stream()
                        .map(ContinuationRefusal::categoryOf)
                        .toList();
        assertEquals(SIX, mapped.size(),
                "a refusal was added or lost without this suite being told");
        assertTrue(mapped.stream().noneMatch(java.util.Objects::isNull),
                "a refusal reaches no category");
    }

    /** How many ways a token fails, which is the list this suite was written against. */
    private static final int SIX = 6;

    @Test
    @DisplayName("the window an omitted argument resolves to is the contract's own default")
    void anOmittedWindowIsTheContractsDefault() {
        final ResultWindow.Initial omitted =
                assertInstanceOf(ResultWindow.Initial.class, ResultWindow.omitted(CONTRACT));
        assertEquals(CONTRACT.value(ContractLimit.DEFAULT_RESULT_LIMIT), omitted.limit());
        assertEquals(0, omitted.offset(), "an omitted window began somewhere other than the start");
    }

    private static ResultWindow.Refusal windowRefusal(long offset, long limit) {
        return assertInstanceOf(ResultWindow.Refused.class,
                ResultWindow.initial(offset, limit, CONTRACT), "the window was accepted").refusal();
    }

    private static ResultWindow.Refusal continuationRefusal(String token) {
        return assertInstanceOf(ResultWindow.Refused.class,
                ResultWindow.continuation(token, CONTRACT),
                "the continuation window was accepted").refusal();
    }

    private static List<String> rows(int count) {
        return IntStream.range(0, count).mapToObj(row -> "row-" + row).toList();
    }

    private static PagedQuery query() {
        return new PagedQuery("query_paths", target(), serving());
    }

    private static DocumentValue.Mapping arguments(String root, String type, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put("root", new DocumentValue.Text(root));
        members.put("primary_node_type", new DocumentValue.Text(type));
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        window.put(ResultWindow.OFFSET, new DocumentValue.Whole(0));
        window.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        members.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(window));
        return new DocumentValue.Mapping(members);
    }

    private static DigestValue digestOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(QueryDigest.Held.class, query().digestOf(arguments),
                "the query digest was refused").digest().value();
    }

    private static QueryDigest theQuery() {
        return assertInstanceOf(QueryDigest.Held.class,
                query().digestOf(arguments("/content", "cq:Page", 10)),
                "the query digest was refused").digest();
    }

    private static QueryDigest anotherQuery() {
        return assertInstanceOf(QueryDigest.Held.class,
                query().digestOf(arguments("/content/elsewhere", "cq:Page", 10)),
                "the query digest was refused").digest();
    }

    private static DigestValue target() {
        return Digest.of("an-author-target".getBytes(StandardCharsets.UTF_8));
    }

    private static KeyRing ring() {
        return KeyRing.initial(KEY);
    }

    private static EventStoreGeneration serving() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation is not one").generation();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
