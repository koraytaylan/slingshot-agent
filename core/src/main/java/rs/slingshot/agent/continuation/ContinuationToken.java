// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * One continuation token: what it says, and the digest binding that to a key this agent holds.
 *
 * <p>A client cannot construct one, cannot alter one without invalidating it, and cannot carry one
 * from one query to another. The bound on how much state a token may carry is universal: there is
 * no single-node, private, or node-local path that would let one deployment carry a different
 * amount than another, because the guarantee would then change the day somebody added a node and
 * the code depending on it would not know.</p>
 *
 * <p>Precedence when several things are wrong at once is fixed rather than whichever check ran
 * first. Integrity comes before staleness, because a tampered token is a different finding from an
 * expired one and reporting the milder of the two would hide the other.</p>
 */
public final class ContinuationToken {

    /** The version every token is derived under, which is inside the integrity digest. */
    public static final String VERSION = "slingshot.agent-continuation/1";

    /** What separates two fields inside the derivation, which no field can carry. */
    public static final byte FIELD_SEPARATOR = 0;

    /** How wide a whole number is written inside the derivation. */
    private static final int WHOLE_NUMBER_BYTES = 8;

    /** The member the integrity digest is carried in. */
    public static final String INTEGRITY = "integrity";

    /** The member the state is carried in. */
    public static final String STATE = "state";

    /** Every member a token document has, and there is no third. */
    public static final List<String> MEMBERS = List.of(INTEGRITY, STATE);

    private final DigestValue integrity;
    private final ContinuationState state;

    private ContinuationToken(DigestValue integrity, ContinuationState state) {
        this.integrity = integrity;
        this.state = state;
    }

    /**
     * Why a token was not honoured, in the fixed precedence the client's own registry declares.
     */
    public enum Refusal {
        /** The token is not the shape a token has. */
        MALFORMED,
        /** No key this agent holds signs this token. */
        INTEGRITY_INVALID,
        /** The token belongs to another partition. */
        WRONG_TARGET,
        /** The token belongs to another query. */
        WRONG_QUERY,
        /** The store was rebuilt since the token was issued. */
        WRONG_GENERATION,
        /** The token has expired. */
        EXPIRED
    }

    /** The result of honouring one: the key it validated under, or the one reason it did not. */
    public sealed interface Outcome permits Honoured, Refused {
    }

    /**
     * A token this agent honours.
     *
     * @param key which of the ring's keys validated it, which says whether a rotation is under way
     */
    public record Honoured(ValidatingKey key) implements Outcome {
    }

    /**
     * A token it does not.
     *
     * @param refusal why it does not
     */
    public record Refused(Refusal refusal) implements Outcome {
    }

    /**
     * Issues the token one state produces under one key.
     *
     * @param state where to resume
     * @param key the key to bind it to
     * @return the token
     */
    public static ContinuationToken issue(ContinuationState state, String key) {
        return new ContinuationToken(integrityOf(state, key), state);
    }

    /**
     * Holds a token that arrived, without having checked it.
     *
     * <p>Named so a caller cannot mistake it for a validated one: reading a token is not the same
     * as honouring one.</p>
     *
     * @param integrity the digest the token arrived with
     * @param state what the token says
     * @return the unvalidated token
     */
    public static ContinuationToken arrived(DigestValue integrity, ContinuationState state) {
        return new ContinuationToken(integrity, state);
    }

    /**
     * What this token says, without having checked it.
     *
     * @return the state it claims
     */
    public ContinuationState unvalidatedState() {
        return state;
    }

    /**
     * The digest binding this token's state to a key.
     *
     * @return the integrity digest
     */
    public DigestValue integrity() {
        return integrity;
    }

    /**
     * How many bytes of state this token occupies, counted the way the client counts them.
     *
     * @return the byte count
     */
    public long stateBytes() {
        return integrity.rendered().length() + state.targetDigest().rendered().length()
                + state.queryDigest().rendered().length();
    }

    /**
     * Honours this token, or says why it is not honoured.
     *
     * <p>Integrity first, so a tampered token never reaches a comparison against data it named — a
     * token that could steer a target or query check before being shown to be forged would be a
     * token doing exactly what forging one is for.</p>
     *
     * @param ring the keys this agent holds
     * @param expectedTarget the partition the query being resumed runs in
     * @param expectedQuery the query being resumed
     * @param serving the incarnation of the store this agent is serving
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the state bound
     * @return the key it validated under, or the one reason it was not honoured
     */
    public Outcome validate(KeyRing ring, DigestValue expectedTarget, QueryDigest expectedQuery,
                            EventStoreGeneration serving, long nowUnixMilliseconds,
                            AgentContract contract) {
        if (stateBytes() > contract.value(
                ContractLimit.MAXIMUM_AGENT_CONTINUATION_KEY_STATE_BYTES)) {
            return new Refused(Refusal.MALFORMED);
        }
        final Optional<ValidatingKey> signing = signingKey(ring, nowUnixMilliseconds);
        if (signing.isEmpty()) {
            return new Refused(Refusal.INTEGRITY_INVALID);
        }
        if (!state.targetDigest().matches(expectedTarget)) {
            return new Refused(Refusal.WRONG_TARGET);
        }
        if (!state.queryDigest().matches(expectedQuery.value())) {
            return new Refused(Refusal.WRONG_QUERY);
        }
        if (!state.generation().equals(serving)) {
            return new Refused(Refusal.WRONG_GENERATION);
        }
        if (nowUnixMilliseconds >= state.expiresAtUnixMilliseconds()) {
            return new Refused(Refusal.EXPIRED);
        }
        return new Honoured(signing.get());
    }

    private Optional<ValidatingKey> signingKey(KeyRing ring, long nowUnixMilliseconds) {
        return ring.keys().stream()
                .filter(key -> integrityOf(state, key).matches(integrity))
                .findFirst()
                .flatMap(key -> ring.validating(key, nowUnixMilliseconds));
    }

    private static DigestValue integrityOf(ContinuationState state, String key) {
        final ByteArrayOutputStream bound = new ByteArrayOutputStream();
        List.of(VERSION.getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8),
                        state.targetDigest().rendered().getBytes(StandardCharsets.UTF_8),
                        state.queryDigest().rendered().getBytes(StandardCharsets.UTF_8),
                        wholeNumber(state.generation().number()),
                        wholeNumber(state.position()),
                        wholeNumber(state.expiresAtUnixMilliseconds()))
                .forEach(field -> {
                    bound.writeBytes(field);
                    bound.write(FIELD_SEPARATOR);
                });
        return Digest.of(bound.toByteArray());
    }

    private static byte[] wholeNumber(long value) {
        // Written as the client writes it: eight bytes, most significant first, so a number is one
        // sequence of bytes on both sides rather than whatever each side's rendering would produce.
        return ByteBuffer.allocate(WHOLE_NUMBER_BYTES).putLong(value).array();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final ContinuationToken token && integrity.matches(token.integrity)
                && state.equals(token.state);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(integrity.rendered(), state);
    }

    @Override
    public String toString() {
        return "continuation at " + state.position();
    }
}
