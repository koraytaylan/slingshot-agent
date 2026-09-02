// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * Which keys an authority holds: the one tokens are issued under, and the one before it while it
 * lives.
 *
 * <p>Keys rotate with the previous one retained. A token issued a moment before a rotation is still
 * a token somebody is holding, so the prior key outlives the longest token issued under it plus the
 * skew two clocks may differ by, and validation tries the current key and then the prior one.</p>
 *
 * <p>Nothing here observes node count and nothing branches on which deployment it is. A single
 * instance is not permitted a cheaper version, because the guarantees would change the day somebody
 * added a node and the code depending on them would not know.</p>
 *
 * @param current the key tokens are issued under now
 * @param prior the key retained from before the last rotation, or nothing retained
 */
public record KeyRing(String current, Prior prior) {

    /** What a ring holds from before its last rotation. */
    public sealed interface Prior permits Retained, NothingRetained {
    }

    /**
     * A key still honoured, and when it stops being.
     *
     * @param key the retained key
     * @param expiresAtUnixMilliseconds when it stops being accepted
     */
    public record Retained(String key, long expiresAtUnixMilliseconds) implements Prior {
    }

    /** A ring that has never rotated, or one whose prior key has been let go. */
    public record NothingRetained() implements Prior {
    }

    /** The result of changing one: the ring, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A ring that satisfies every bound.
     *
     * @param ring the ring
     */
    public record Held(KeyRing ring) implements Outcome {
    }

    /**
     * A ring that does not, or a change that is not permitted.
     *
     * @param refusal why it is not
     */
    public record Refused(KeyRingRefusal refusal) implements Outcome {
    }

    /**
     * A ring holding one key and nothing retained.
     *
     * @param current the key tokens are issued under
     * @return the ring
     */
    public static KeyRing initial(String current) {
        return new KeyRing(current, new NothingRetained());
    }

    /**
     * Every key this ring holds, current first.
     *
     * @return the keys
     */
    public List<String> keys() {
        return prior instanceof final Retained retained
                ? List.of(current, retained.key())
                : List.of(current);
    }

    /**
     * How many bytes this ring occupies.
     *
     * @return the byte count
     */
    public long recordBytes() {
        return keys().stream()
                .mapToLong(String::length)
                .sum();
    }

    /**
     * Whether this ring fits the bounds the contract declares.
     *
     * @param contract the authenticated contract
     * @return the one reason it does not, or nothing where it does
     */
    public Optional<KeyRingRefusal> unbounded(AgentContract contract) {
        final long key = contract.value(ContractLimit.MAXIMUM_AGENT_CONTINUATION_KEY_STATE_BYTES);
        final long record =
                contract.value(ContractLimit.MAXIMUM_CONTINUATION_KEY_AUTHORITY_RECORD_BYTES);
        final Optional<String> long_ = keys().stream()
                .filter(held -> held.length() > key)
                .findFirst();
        if (long_.isPresent()) {
            return Optional.of(new KeyRingRefusal(KeyRingRefusal.Failure.KEY_TOO_LONG,
                    "a key holds at most " + key + " bytes and this holds " + long_.get().length()));
        }
        if (recordBytes() > record) {
            return Optional.of(new KeyRingRefusal(KeyRingRefusal.Failure.RECORD_TOO_LONG,
                    "a record holds at most " + record + " bytes and this holds " + recordBytes()));
        }
        return Optional.empty();
    }

    /**
     * Which key validates one that was presented, when either does.
     *
     * <p>Current first, then prior. A token that only the prior key validates is one issued before
     * the last rotation and still inside its retention, which is a token to honour rather than a
     * token to refuse.</p>
     *
     * @param presented the key a token's integrity was found under
     * @param nowUnixMilliseconds what this side's clock says
     * @return which key it is, or nothing where it is neither
     */
    public Optional<ValidatingKey> validating(String presented, long nowUnixMilliseconds) {
        if (presented.equals(current)) {
            return Optional.of(ValidatingKey.CURRENT);
        }
        if (!(prior instanceof final Retained retained)) {
            return Optional.empty();
        }
        if (presented.equals(retained.key())
                && nowUnixMilliseconds < retained.expiresAtUnixMilliseconds()) {
            return Optional.of(ValidatingKey.PRIOR);
        }
        return Optional.empty();
    }

    /**
     * This ring with another key current and the old one retained.
     *
     * <p>Refused while a previous rotation's key is still retained: two rotations inside one
     * retention window would strand every token issued under the key that fell off the end.</p>
     *
     * @param next the key to issue under from now on
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the retention and the bounds
     * @return the rotated ring, or the one reason there is none
     */
    public Outcome rotated(String next, long nowUnixMilliseconds, AgentContract contract) {
        if (prior instanceof final Retained retained
                && nowUnixMilliseconds < retained.expiresAtUnixMilliseconds()) {
            return new Refused(new KeyRingRefusal(KeyRingRefusal.Failure.PRIOR_STILL_RETAINED,
                    "the previous key is retained until " + retained.expiresAtUnixMilliseconds()
                            + ", and rotating now would strand every token issued under it"));
        }
        final long retention =
                contract.value(ContractLimit.CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS);
        final KeyRing rotated = new KeyRing(next,
                new Retained(current, nowUnixMilliseconds + retention));
        final Optional<KeyRingRefusal> unbounded = rotated.unbounded(contract);
        return unbounded.<Outcome>map(Refused::new).orElseGet(() -> new Held(rotated));
    }
}
