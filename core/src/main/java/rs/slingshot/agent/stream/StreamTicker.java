// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

/**
 * What time it is to a stream, and how it waits.
 *
 * <p>A seam rather than a call to the clock, because a suite proving that a heartbeat goes at the
 * declared interval and that a session ends at exactly its bound cannot do it by sleeping: a test
 * that sleeps for a fixed span proves what that machine did that afternoon. Here the suite advances
 * time itself and the same loop runs.</p>
 *
 * <p>Serialisable because a servlet is, and a component holding one is holding it in a field the
 * platform is entitled to serialise. There is nothing in a ticker to serialise, which is the
 * point.</p>
 */
public interface StreamTicker extends java.io.Serializable {

    /**
     * What this side's clock says, for an instant that goes on the wire.
     *
     * <p>A wall clock, because an instant a client compares with its own has to be the same kind of
     * instant. It is never used to measure how long something has taken: a wall clock is corrected,
     * and a correction backwards turns a bounded wait into an unbounded one.</p>
     *
     * @return the milliseconds since the epoch
     */
    long milliseconds();

    /**
     * How much time has passed, from a source nothing corrects.
     *
     * <p>Every duration this stream measures — how long it has been open, how long since it last
     * said anything — is measured with this rather than by subtracting two wall-clock readings. A
     * time service correcting a clock backwards would otherwise extend a session past the bound it
     * publishes, and correcting it forwards would end one that had barely started; neither is
     * something a client could be told about, because from the outside both look like the
     * connection behaving oddly.</p>
     *
     * <p>The value means nothing on its own. Only differences between two readings of it do, which
     * is what makes it the right source for a duration and the wrong one for an instant.</p>
     *
     * @return a reading whose difference from an earlier one is the time between them
     */
    long elapsedMilliseconds();

    /**
     * Waits before looking again, which is the only place a stream is idle.
     *
     * @param milliseconds how long to wait for
     */
    void pause(long milliseconds);
}
