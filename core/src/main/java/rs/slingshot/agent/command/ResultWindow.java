// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which page of an enumeration a caller is asking for.
 *
 * <p>One window for every command that enumerates anything, because a per-command notion of "how
 * much" is a per-command opportunity to forget a bound, and the first command that forgot one would
 * let a caller ask for the whole repository.</p>
 *
 * <p>It has exactly two shapes and the second says nothing but where to resume. The offset and the
 * limit an enumeration began under travel inside its token, so a caller cannot widen a page it is
 * already half way through by restating a limit — and a continuation that carried an offset or a
 * limit would be carrying two answers to one question, which is why one arriving with either is
 * refused even when the values it carries are the ones the enumeration already had.</p>
 *
 * <h2>What is refused and what is merely served short</h2>
 *
 * <p>These are two different things and the difference matters. A window asking for nothing at all
 * is refused: a page of no rows answers no question anybody meant to ask, and returning one would
 * make an empty page ambiguous between "you asked for none" and "there are none". A window asking
 * for more than the contract permits is also refused, and is refused rather than quietly reduced
 * because the client refuses to send one — so a request that arrives above the contract's maximum
 * did not come from a conforming client, and the two halves agreeing about which requests exist is
 * worth more here than being accommodating to a caller that is already out of contract.</p>
 *
 * <p>Serving fewer rows than a permitted window asked for is not a refusal at all. A command stops
 * at its own result bound and hands back what it has with a token, which is ordinary paging: the
 * window is the most a caller wants, never the least it will accept.</p>
 */
public sealed interface ResultWindow permits ResultWindow.Initial, ResultWindow.Continuation {

    /** The member a window is carried in, which is the one argument a token does not digest. */
    String ARGUMENT_MEMBER = "result_window";

    /** The member naming which of the two shapes a window has. */
    String MODE = "mode";

    /** How the shape that begins an enumeration is spelled. */
    String INITIAL_MODE = "initial";

    /** How the shape that resumes one is spelled. */
    String CONTINUATION_MODE = "continuation";

    /** The member an initial window carries its offset in. */
    String OFFSET = "offset";

    /** The member an initial window carries its limit in. */
    String LIMIT = "limit";

    /** The member a continuation window carries its token in. */
    String TOKEN = "continuation_token";

    /**
     * Every member a window's own document has, which a command borrows rather than restating.
     *
     * <p>A window is a document inside another document, and the schema that declares an argument
     * declares the window's members alongside the argument's own. Nine commands carry one; nine
     * copies of these four names is nine places for them to drift.</p>
     */
    java.util.List<String> MEMBERS = java.util.List.of(LIMIT, MODE, OFFSET, TOKEN);

    /**
     * The window that begins an enumeration.
     *
     * @param offset how many fully evaluated matches to skip before emitting one
     * @param limit how many matches this page may carry
     */
    record Initial(long offset, long limit) implements ResultWindow {
    }

    /**
     * The window that resumes one.
     *
     * @param continuationToken the bytes naming where to resume, exactly as they arrived
     */
    record Continuation(String continuationToken) implements ResultWindow {
    }

    /** Why a window is not one this contract defines. Each is distinct because each has a fix. */
    enum Refusal {
        /** The mode is neither of the two this contract defines. */
        UNKNOWN_MODE,
        /** An initial window asked for no matches at all. */
        LIMIT_ZERO,
        /** An initial window asked for more matches than the contract allows. */
        LIMIT_ABOVE_MAXIMUM,
        /** An initial window asked to skip further than the contract allows. */
        OFFSET_ABOVE_MAXIMUM,
        /** A continuation carried a field the token it names already fixes. */
        CONTINUATION_NOT_ALONE,
        /** A continuation carried no token, which is the only thing it carries. */
        CONTINUATION_INCOMPLETE,
        /** The token carries no bytes. */
        TOKEN_EMPTY,
        /** The token carries a character no compact token carries. */
        TOKEN_CONTROL_CHARACTER,
        /** The token is longer than the contract allows. */
        TOKEN_TOO_LONG
    }

    /** The result of reading one: the window, or the one reason there is none. */
    sealed interface Outcome permits Held, Refused {
    }

    /**
     * A window this contract defines.
     *
     * @param window the window
     */
    record Held(ResultWindow window) implements Outcome {
    }

    /**
     * One that it does not.
     *
     * @param refusal why it does not
     */
    record Refused(Refusal refusal) implements Outcome {
    }

    /**
     * The window an omitted argument resolves to.
     *
     * @param contract the authenticated contract, which declares both defaults
     * @return the first page, at the contract's own default size
     */
    static ResultWindow omitted(AgentContract contract) {
        return new Initial(BEGINNING, contract.value(ContractLimit.DEFAULT_RESULT_LIMIT));
    }

    /** Where an enumeration begins when the caller names no offset. */
    long BEGINNING = 0;

    /**
     * The window one argument document asks for, which is the default where it names none.
     *
     * <p>The client's own schemas make the window optional on every command that pages, so an
     * argument without one is not incomplete — it is a caller who did not care which page, and the
     * answer to that is the first one at the contract's own size. Written once because a per-command
     * reading of an optional member is a per-command opportunity to make an absent window a
     * refusal.</p>
     *
     * @param arguments the whole argument document
     * @param contract the authenticated contract, which declares every bound and both defaults
     * @return the window, or the one reason there is none
     */
    static Outcome asked(DocumentValue.Mapping arguments, AgentContract contract) {
        return arguments.member(ARGUMENT_MEMBER)
                .map(window -> of(window, contract))
                .orElseGet(() -> new Held(omitted(contract)));
    }

    /**
     * Reads the window one caller wrote down.
     *
     * <p>The two shapes are told apart by the mode they declare rather than by which members
     * happen to be present, so a caller who meant one and wrote the other is refused instead of
     * being given whichever the reader guessed. A continuation carrying an offset or a limit is
     * refused even when the values are the ones its own enumeration began under: a member that is
     * always ignored is a member somebody will eventually believe is honoured.</p>
     *
     * @param window the window as the caller wrote it
     * @param contract the authenticated contract, which declares every bound
     * @return the window, or the one reason there is none
     */
    static Outcome of(DocumentValue window, AgentContract contract) {
        if (!(window instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.UNKNOWN_MODE);
        }
        if (!(mapping.member(MODE).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text mode)) {
            return new Refused(Refusal.UNKNOWN_MODE);
        }
        return switch (mode.value()) {
            case INITIAL_MODE -> initialOf(mapping, contract);
            case CONTINUATION_MODE -> continuationOf(mapping, contract);
            default -> new Refused(Refusal.UNKNOWN_MODE);
        };
    }

    private static Outcome initialOf(DocumentValue.Mapping mapping, AgentContract contract) {
        if (mapping.member(TOKEN).isPresent()) {
            return new Refused(Refusal.UNKNOWN_MODE);
        }
        if (!(mapping.member(OFFSET).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Whole offset)
                || !(mapping.member(LIMIT).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Whole limit)) {
            return new Refused(Refusal.LIMIT_ZERO);
        }
        return initial(offset.value(), limit.value(), contract);
    }

    private static Outcome continuationOf(DocumentValue.Mapping mapping, AgentContract contract) {
        if (mapping.member(OFFSET).isPresent() || mapping.member(LIMIT).isPresent()) {
            return new Refused(Refusal.CONTINUATION_NOT_ALONE);
        }
        if (!(mapping.member(TOKEN).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text token)) {
            return new Refused(Refusal.CONTINUATION_INCOMPLETE);
        }
        return continuation(token.value(), contract);
    }

    /**
     * Reads the window one offset and limit name.
     *
     * @param offset how many matches to skip
     * @param limit how many matches the page may carry
     * @param contract the authenticated contract, which declares both maxima
     * @return the window, or the one reason there is none
     */
    static Outcome initial(long offset, long limit, AgentContract contract) {
        if (limit == NONE_AT_ALL) {
            return new Refused(Refusal.LIMIT_ZERO);
        }
        if (limit > contract.value(ContractLimit.MAXIMUM_RESULT_LIMIT)) {
            return new Refused(Refusal.LIMIT_ABOVE_MAXIMUM);
        }
        if (offset > contract.value(ContractLimit.MAXIMUM_RESULT_OFFSET)) {
            return new Refused(Refusal.OFFSET_ABOVE_MAXIMUM);
        }
        return new Held(new Initial(offset, limit));
    }

    /** The limit a caller cannot ask for, because a page of nothing answers nothing. */
    long NONE_AT_ALL = 0;

    /**
     * Reads the window one token names.
     *
     * @param token the bytes naming where to resume
     * @param contract the authenticated contract, which declares how large a token may be
     * @return the window, or the one reason there is none
     */
    static Outcome continuation(String token, AgentContract contract) {
        if (token.isEmpty()) {
            return new Refused(Refusal.TOKEN_EMPTY);
        }
        if (token.chars().anyMatch(Character::isISOControl)) {
            return new Refused(Refusal.TOKEN_CONTROL_CHARACTER);
        }
        if (token.length() > contract.value(ContractLimit.MAXIMUM_CONTINUATION_TOKEN_BYTES)) {
            return new Refused(Refusal.TOKEN_TOO_LONG);
        }
        return new Held(new Continuation(token));
    }
}
