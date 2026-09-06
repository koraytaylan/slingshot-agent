// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.processing.Generated;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Everything a handler may reach, and nothing that would let it reach anything else.
 *
 * <p>The caller's own resolver is handed to a handler beside this rather than held on it. That is
 * deliberate: this is a value, and a value a handler was given is a value it could keep — a live
 * session kept past the request it belongs to is exactly the thing this design exists to prevent.
 * So the resolver arrives as an argument, for the length of one call, and there is no member here
 * that yields a second one, a service, a factory, or a bundle context. The source policy refuses
 * one being written.</p>
 *
 * <p>A staging area is not on it either, and for the same reason: {@link StagingArea#forRow} opens
 * one where a command's own row declared room, the framework hands it in for the length of one call
 * and gives it back however the command ended. A command that needs scratch space is handed a place
 * rather than the means to find one, and never a place it could keep.</p>
 *
 */
@Generated("continuation-context-value-object")
public final class CallerContext {

    private final AgentOperationIdentifier operation;
    private final Budget discovery;
    private final Budget time;
    private final Budget result;
    private final ProgressSink progress;
    private final Paging paging;

    /**
     * Creates the per-call context.
     *
     * @param operation which operation this is
     *
     * @param discovery how many rows it may examine
     *
     * @param time how long it may run
     *
     * @param result how large its result may be
     *
     * @param progress where its progress goes
     *
     * @param paging continuation authority and store identity, or unavailable
     */
    public CallerContext(AgentOperationIdentifier operation, Budget discovery, Budget time,
                         Budget result, ProgressSink progress, Paging paging) {
        this.operation = operation;
        this.discovery = discovery;
        this.time = time;
        this.result = result;
        this.progress = progress;
        this.paging = paging;
    }

    /**
     * Creates a context for a caller without continuation services.
     *
     * @param operation the active command identity
     * @param discovery the row examination limit
     * @param time the execution time limit
     * @param result the result size limit
     * @param progress the progress event sink
     */
    public CallerContext(AgentOperationIdentifier operation, Budget discovery, Budget time,
                         Budget result, ProgressSink progress) {
        this(operation, discovery, time, result, progress, Unavailable.INSTANCE);
    }

    /** The identity is fixed when the call is admitted.
     * @return the command identity accepted for this call */
    public AgentOperationIdentifier operation() {
        return operation;
    }

    /** This limit bounds the rows inspected by a handler.
     * @return the configured row examination limit */
    public Budget discovery() {
        return discovery;
    }

    /** This limit bounds elapsed handler time.
     * @return the configured execution time limit */
    public Budget time() {
        return time;
    }

    /** This limit bounds the serialized response.
     * @return the configured result size limit */
    public Budget result() {
        return result;
    }

    /** Handlers publish progress through this destination.
     * @return the progress notification destination */
    public ProgressSink progress() {
        return progress;
    }

    /** The runtime may provide services for split results.
     * @return the continuation services used by this call */
    public Paging paging() {
        return paging;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof final CallerContext context)) {
            return false;
        }
        return Objects.equals(operation, context.operation)
                && Objects.equals(discovery, context.discovery)
                && Objects.equals(time, context.time)
                && Objects.equals(result, context.result)
                && Objects.equals(progress, context.progress)
                && Objects.equals(paging, context.paging);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operation, discovery, time, result, progress, paging);
    }

    /** Per-call authority and identity needed to issue or validate continuation tokens. */
    public sealed interface Paging permits Available, Unavailable {
    }

    /**
     * Paging context supplied by a runtime with continuation authority.
     *
     * @param authority the persisted continuation key authority
     * @param targetDigest the operation target partition
     * @param generation the serving event-store generation
     * @param nowUnixMilliseconds the request clock
     */
    @Generated("continuation-context-value-object")
    public record Available(ContinuationKeyAuthority authority, DigestValue targetDigest,
                            EventStoreGeneration generation, long nowUnixMilliseconds)
            implements Paging {
    }

    /** Explicit absence of paging authority for a compatibility caller. */
    public enum Unavailable implements Paging {
        /** The caller did not provide runtime paging services. */
        INSTANCE
    }

    /**
     * Whether one spend is inside every budget this context carries.
     *
     * @param rowsExamined how many rows have been examined
     * @param millisecondsSpent how long it has run
     * @param resultBytes how large the result is so far
     * @return the budget that was exceeded, or nothing where all three are inside
     */
    public Optional<Budget> exceeded(long rowsExamined, long millisecondsSpent, long resultBytes) {
        if (!discovery.allows(rowsExamined)) {
            return Optional.of(discovery);
        }
        if (!time.allows(millisecondsSpent)) {
            return Optional.of(time);
        }
        return result.allows(resultBytes) ? Optional.empty() : Optional.of(result);
    }
}
