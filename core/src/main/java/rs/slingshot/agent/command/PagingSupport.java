// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Generated;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.continuation.QueryDigest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/** The common, verified window operation used by every paged command. */
@Generated("paging-support")
public final class PagingSupport {

    private PagingSupport() {
    }

    /**
     * One page decision, including the token that reaches its successor where one exists.
     *
     * @param <R> the result row type
     * @param rows the rows to return
     * @param continuationToken the successor token, or empty at the end
     */
    public record Page<R>(List<R> rows, String continuationToken) {

        /** Holds rows apart from the mutable list used to assemble them. */
        public Page {
            rows = List.copyOf(rows);
        }
    }

    /** A page decision or the one refusal that prevented it. */
    public sealed interface Outcome<R> permits Accepted, Refused {
    }

    /**
     * A page that was validated and may be returned.
     *
     * @param <R> the result row type
     * @param page the accepted page
     */
    public record Accepted<R>(Page<R> page) implements Outcome<R> {
    }

    /**
     * A paging request that could not be honoured.
     *
     * @param <R> the result row type
     * @param category the declared refusal category
     * @param detail the observed reason
     */
    public record Refused<R>(String category, String detail) implements Outcome<R> {
    }

    /**
     * Applies a window, validates a continuation, and issues its successor token.
     *
     * @param entries the complete bounded result in its stable order
     * @param window the requested page
     * @param wireName the command's wire name
     * @param arguments the command arguments, including the window
     * @param context the per-call continuation authority and identity
     * @param contract the authenticated bounds
     * @param <R> the result row type
     * @return the page or a refusal
     */
    public static <R> Outcome<R> page(List<R> entries, ResultWindow window, String wireName,
                                      DocumentValue.Mapping arguments, CallerContext context,
                                      AgentContract contract) {
        final Optional<PagedQuery> query = query(wireName, context);
        final QueryDigest.Outcome digest = query.map(value -> value.digestOf(arguments))
                .orElseGet(() -> QueryDigest.of(wireName, arguments));
        if (!(digest instanceof final QueryDigest.Held held)) {
            return new Refused<>("continuation_token_malformed",
                    "the query arguments cannot be represented canonically");
        }
        final long offset = offset(window, held.digest(), context, contract);
        if (offset < 0) {
            return new Refused<>("continuation_token_integrity_invalid",
                    "the continuation token was not accepted");
        }
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit() : contract.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        final List<R> fromOffset = entries.stream().skip(offset).toList();
        final PagedQuery.Page<R> page = PagedQuery.pageOf(fromOffset, limit, offset);
        final Optional<String> token = token(page, wireName, held.digest(), context, contract);
        if (page.following() instanceof PagedQuery.More
                && context.paging() instanceof CallerContext.Available) {
            return new Refused<>("continuation_token_integrity_invalid",
                    "continuation authority is unavailable");
        }
        return new Accepted<>(new Page<>(page.rows(), token.orElse("")));
    }

    private static Optional<PagedQuery> query(String wireName, CallerContext context) {
        return context.paging() instanceof final CallerContext.Available paging
                ? Optional.of(new PagedQuery(wireName, paging.targetDigest(), paging.generation()))
                : Optional.empty();
    }

    private static long offset(ResultWindow window, QueryDigest query, CallerContext context,
                               AgentContract contract) {
        if (window instanceof final ResultWindow.Initial initial) {
            return initial.offset();
        }
        if (!(context.paging() instanceof final CallerContext.Available paging)) {
            return -1;
        }
        final ResultWindow.Continuation continuation = (ResultWindow.Continuation) window;
        final BoundedDocumentReader.Outcome read = BoundedDocumentReader.read(
                continuation.continuationToken().getBytes(StandardCharsets.UTF_8),
                BoundedDocumentReader.Bounds.from(contract));
        if (!(read instanceof final BoundedDocumentReader.Read parsed)
                || !(ContinuationToken.read(parsed.value()) instanceof final ContinuationToken.Read token)
                || !(paging.authority().read() instanceof final ContinuationKeyAuthority.Read authority)) {
            return -1;
        }
        final ContinuationToken.Outcome validated = token.token().validate(authority.ring(),
                paging.targetDigest(), query, paging.generation(), paging.nowUnixMilliseconds(),
                contract);
        return validated instanceof ContinuationToken.Honoured
                ? token.token().unvalidatedState().position() : -1;
    }

    private static Optional<String> token(PagedQuery.Page<?> page, String wireName,
                                          QueryDigest query, CallerContext context,
                                          AgentContract contract) {
        if (!(page.following() instanceof PagedQuery.More)) {
            return Optional.of("");
        }
        if (!(context.paging() instanceof final CallerContext.Available paging)
                || !(paging.authority().read() instanceof final ContinuationKeyAuthority.Read read)) {
            return Optional.empty();
        }
        return new PagedQuery(wireName, paging.targetDigest(), paging.generation())
                .tokenFor(page, query, read.ring(), paging.nowUnixMilliseconds(), contract)
                .map(ContinuationToken::rendered);
    }
}
