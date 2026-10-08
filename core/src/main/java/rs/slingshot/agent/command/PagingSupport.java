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

    /** A page decision or the one refusal that prevented it.
     * @param <R> the result row type
     */
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
        final Preparation prepared = prepare(window, wireName, arguments, context, contract);
        if (prepared instanceof final WindowRefused refused) {
            return new Refused<>(refused.category(), refused.detail());
        }
        return page(entries, (Ready) prepared, wireName, context, contract);
    }

    /** A window validated before a handler begins repository discovery. */
    public sealed interface Preparation permits Ready, WindowRefused {
    }

    /**
     * Validated window parameters and the query they are bound to.
     * @param offset where enumeration resumes
     * @param limit maximum matches returned
     * @param digest the authenticated query digest
     * @param expiresAtUnixMilliseconds the fixed expiry inherited by every successor token
     */
    public record Ready(long offset, long limit, QueryDigest digest,
                        long expiresAtUnixMilliseconds) implements Preparation {
    }

    /**
     * The refusal that prevents any repository discovery.
     * @param category the declared continuation failure
     * @param detail the reason, without reflecting token contents
     */
    public record WindowRefused(String category, String detail) implements Preparation {
    }

    /**
     * Validates continuation authority before a caller touches repository content.
     * @param window the requested window
     * @param wireName the command being paged
     * @param arguments its complete arguments
     * @param context per-call continuation authority and identity
     * @param contract authenticated bounds
     * @return the prepared window or the exact validation refusal
     */
    public static Preparation prepare(ResultWindow window, String wireName,
                                       DocumentValue.Mapping arguments, CallerContext context,
                                       AgentContract contract) {
        final Optional<PagedQuery> query = query(wireName, context);
        final QueryDigest.Outcome digest = query.map(value -> value.digestOf(arguments))
                .orElseGet(() -> QueryDigest.of(wireName, arguments));
        if (!(digest instanceof final QueryDigest.Held held)) {
            return refused(ContinuationToken.Refusal.MALFORMED);
        }
        return switch (window) {
            case ResultWindow.Initial initial ->
                    new Ready(initial.offset(), initial.limit(), held.digest(),
                            expiresAt(context, contract));
            case ResultWindow.Continuation resumed ->
                    continuation(resumed, held.digest(), context, contract);
        };
    }

    /**
     * Applies an already validated window and requires a token whenever rows remain.
     * @param entries the complete bounded result in stable order
     * @param prepared the previously validated window
     * @param wireName the command being paged
     * @param context the per-call continuation authority
     * @param contract authenticated bounds
     * @param <R> the row type
     * @return a page or an authority refusal
     */
    public static <R> Outcome<R> page(List<R> entries, Ready prepared, String wireName,
                                      CallerContext context, AgentContract contract) {
        final List<R> fromOffset = entries.stream().skip(prepared.offset())
                .limit(prepared.limit() + 1).toList();
        final PagedQuery.Page<R> page = PagedQuery.pageOf(fromOffset, prepared.limit(),
                prepared.offset());
        final Optional<String> token = token(page, wireName, prepared, context);
        if (page.following() instanceof PagedQuery.More && token.isEmpty()) {
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

    private static long expiresAt(CallerContext context, AgentContract contract) {
        return context.paging() instanceof final CallerContext.Available paging
                ? paging.nowUnixMilliseconds()
                        + contract.value(ContractLimit.CONTINUATION_TOKEN_LIFETIME_MILLISECONDS)
                : 0;
    }

    private static Preparation continuation(ResultWindow.Continuation continuation, QueryDigest query,
                                            CallerContext context, AgentContract contract) {
        if (!(context.paging() instanceof final CallerContext.Available paging)) {
            return refused(ContinuationToken.Refusal.INTEGRITY_INVALID);
        }
        final BoundedDocumentReader.Outcome read = BoundedDocumentReader.read(
                continuation.continuationToken().getBytes(StandardCharsets.UTF_8),
                BoundedDocumentReader.Bounds.from(contract));
        if (!(read instanceof final BoundedDocumentReader.Read parsed)
                || !(ContinuationToken.read(parsed.value()) instanceof final ContinuationToken.Read token)) {
            return refused(ContinuationToken.Refusal.MALFORMED);
        }
        if (!(paging.authority().read() instanceof final ContinuationKeyAuthority.Read authority)) {
            return refused(ContinuationToken.Refusal.INTEGRITY_INVALID);
        }
        final ContinuationToken.Outcome validated = token.token().validate(authority.ring(),
                paging.targetDigest(), query, paging.generation(), paging.nowUnixMilliseconds(),
                contract);
        if (validated instanceof final ContinuationToken.Refused refusal) {
            return refused(refusal.refusal());
        }
        return new Ready(token.token().unvalidatedState().position(),
                token.token().unvalidatedState().initialResultLimit(), query,
                token.token().unvalidatedState().expiresAtUnixMilliseconds());
    }

    private static WindowRefused refused(ContinuationToken.Refusal refusal) {
        final String category = switch (refusal) {
            case MALFORMED -> "continuation_token_malformed";
            case INTEGRITY_INVALID -> "continuation_token_integrity_invalid";
            case WRONG_TARGET -> "continuation_token_wrong_target";
            case WRONG_QUERY -> "continuation_token_wrong_query";
            case WRONG_GENERATION, EXPIRED -> "continuation_token_expired";
        };
        return new WindowRefused(category, ContinuationRefusal.detailOf(refusal));
    }

    private static Optional<String> token(PagedQuery.Page<?> page, String wireName,
                                          Ready prepared, CallerContext context) {
        if (!(page.following() instanceof PagedQuery.More)) {
            return Optional.of("");
        }
        if (!(context.paging() instanceof final CallerContext.Available paging)
                || !(paging.authority().read() instanceof final ContinuationKeyAuthority.Read read)) {
            return Optional.empty();
        }
        return new PagedQuery(wireName, paging.targetDigest(), paging.generation())
                .tokenFor(page, prepared.digest(), read.ring(), prepared.limit(),
                        prepared.expiresAtUnixMilliseconds())
                .map(ContinuationToken::rendered);
    }
}
