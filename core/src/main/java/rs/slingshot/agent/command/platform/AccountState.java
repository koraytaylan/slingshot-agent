// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Whether an account may be used.
 *
 * <p>Named for the same reason the suspension state is, with more at stake: {@code disabled(false)}
 * is a double negative in the one place a mistake locks somebody out of their own instance. And
 * disabling is not deleting — a disabled account keeps everything it owns and everything that
 * points at it, which is why it is a state rather than a step on the way to removal.</p>
 */
public enum AccountState {

    /** It may be used. */
    ENABLED("enabled"),

    /** It may not, and everything it owns is still there. */
    DISABLED("disabled");

    private final String spelling;

    AccountState(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How the wire spells this state.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The state one argument names.
     *
     * <p>The client carries this as a flag, and this reads it into a name. {@code disabled(false)}
     * at a call site is a double negative in the one place a mistake locks somebody out of their
     * own instance, so what arrives on the wire is the client's shape and what the rest of this
     * build passes around is a word.</p>
     *
     * @param spelled what the caller sent
     * @return the state, or nothing where that is not a flag
     */
    public static Optional<AccountState> of(DocumentValue spelled) {
        return spelled instanceof final DocumentValue.Flag flag
                ? Optional.of(flag.value() == DocumentValue.Truth.TRUE ? DISABLED : ENABLED)
                : Optional.empty();
    }

    /**
     * How the client carries this state, which is a flag saying whether the account is disabled.
     *
     * @return the flag
     */
    public DocumentValue.Flag flag() {
        return new DocumentValue.Flag(
                this == DISABLED ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE);
    }

    /**
     * The state one spelling names.
     *
     * @param spelled what was written
     * @return the state, or nothing where nothing is spelled that way
     */
    public static Optional<AccountState> named(String spelled) {
        return Arrays.stream(values())
                .filter(state -> state.spelling.equals(spelled))
                .findFirst();
    }

    /**
     * Both states, spelled as the wire spells them.
     *
     * @return the spellings, in declaration order
     */
    public static List<String> spellings() {
        return Arrays.stream(values()).map(AccountState::spelling).toList();
    }
}
