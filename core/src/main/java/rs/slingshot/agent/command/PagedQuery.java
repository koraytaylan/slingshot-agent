// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.continuation.ContinuationState;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.QueryDigest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One page of an enumeration, and the token that reaches the next one.
 *
 * <p>A token is issued when rows remain and not otherwise, so its absence is a definite end rather
 * than an unknown one. A caller that received a page with no token has seen everything there was to
 * see, and never has to ask again to find that out.</p>
 *
 * <p>The token is bound to the query it came from, because a position in one result set is a
 * perfectly plausible position in another: resuming query B at query A's position would silently
 * answer a question nobody asked, with rows that look entirely reasonable. So the digest of the
 * query travels inside the token and is compared before a row is read, and a token carried across
 * is refused as belonging to another query rather than as damaged.</p>
 *
 * <h2>Which arguments the digest covers</h2>
 *
 * <p>Every argument except the window. The window is the one argument that legitimately differs
 * between two pages of one enumeration — it is how the caller asked for this page rather than part
 * of what they asked about — so digesting it would make every token wrong for its own successor.
 * Everything else goes in, including arguments this build believes affect neither which rows come
 * back nor their order, because whether an argument affects paging is a property of a command's
 * implementation and this has to hold for commands that do not exist yet.</p>
 *
 * @param commandWireName the command being enumerated, which is inside the query's own digest
 * @param targetDigest the partition the enumeration runs in
 * @param generation the incarnation of the store it runs against
 */
public record PagedQuery(String commandWireName, DigestValue targetDigest,
                         EventStoreGeneration generation) {

    /**
     * The digest of one query, taken over every argument but the window.
     *
     * @param arguments every argument the command was asked with, window included
     * @return the digest, or the one reason there is none
     */
    public QueryDigest.Outcome digestOf(DocumentValue.Mapping arguments) {
        return QueryDigest.of(commandWireName, withoutTheWindow(arguments));
    }

    private static DocumentValue.Mapping withoutTheWindow(DocumentValue.Mapping arguments) {
        final SequencedMap<String, DocumentValue> kept = new LinkedHashMap<>(arguments.members());
        kept.remove(ResultWindow.ARGUMENT_MEMBER);
        return new DocumentValue.Mapping(kept);
    }

    /**
     * The page one window asks for, given everything the enumeration found.
     *
     * <p>The rows are what the command produced for this window and one more, where there was one.
     * That extra row is never served: it is how this side knows whether to issue a token, and
     * asking for one more row is cheaper and more truthful than asking the store how many rows
     * there are in total.</p>
     *
     * @param <R> what one row is, which this side never looks inside
     * @param found the rows the command produced, at most one more than the window asked for
     * @param limit how many matches the window asked for
     * @param at where in the enumeration this page began
     * @return what to serve, and whether anything follows it
     */
    public static <R> Page<R> pageOf(List<R> found, long limit, long at) {
        final boolean more = found.size() > limit;
        final List<R> served = more ? List.copyOf(found.subList(0, (int) limit))
                : List.copyOf(found);
        return new Page<>(served, more ? new More(at + served.size()) : new Nothing());
    }

    /** Whether anything follows a page. */
    public sealed interface Following permits More, Nothing {
    }

    /**
     * Something does, beginning here.
     *
     * @param at where the next page begins
     */
    public record More(long at) implements Following {
    }

    /** Nothing does, which is why the page carries no token. */
    public record Nothing() implements Following {
    }

    /**
     * One page of an enumeration.
     *
     * @param <R> what one row is, which this side never looks inside
     * @param rows what to serve, which never includes the row that proved more exist
     * @param following whether anything follows it
     */
    public record Page<R>(List<R> rows, Following following) {

        /**
         * Holds the rows apart from whatever the caller still has a reference to.
         *
         * <p>A page is an answer that has been decided. Keeping the list the command handed over
         * would leave the served rows changeable by whatever built them, after the decision about
         * what follows this page was already taken on their count.</p>
         */
        public Page {
            rows = List.copyOf(rows);
        }
    }

    /**
     * The token that reaches the page after this one, where there is one.
     *
     * <p>The token does not carry the size the enumeration began under. The state a token holds is
     * a document both halves of this protocol declare, closed at five members by the client's own
     * schema, and this side does not get to add a sixth to it. So a resumed page is served at the
     * size the resuming request resolves to rather than at the one its first page used, and a
     * caller who began with a page of twenty-five and resumed is served the default. That is a
     * protocol gap rather than an implementation choice, and closing it means the client declaring
     * the member — its own result-window module already names an {@code initial_result_limit} in
     * the payload it specifies, which its schema does not carry.</p>
     *
     * @param page the page just served
     * @param query the digest of the query it came from
     * @param ring the keys this agent holds, whose current key signs the token
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares how long a token lives
     * @return the token where rows remain, and nothing where none do
     */
    public Optional<ContinuationToken> tokenFor(Page<?> page, QueryDigest query, KeyRing ring,
                                                long nowUnixMilliseconds, AgentContract contract) {
        if (!(page.following() instanceof final More more)) {
            return Optional.empty();
        }
        return Optional.of(ContinuationToken.issue(
                new ContinuationState(generation, targetDigest, query.value(), more.at(),
                        nowUnixMilliseconds + contract.value(
                                ContractLimit.CONTINUATION_TOKEN_LIFETIME_MILLISECONDS)),
                ring.current()));
    }
}
