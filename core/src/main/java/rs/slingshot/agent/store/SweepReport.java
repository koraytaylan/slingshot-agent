// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

/**
 * What one sweep did, written so that two sweeps over one store state produce identical bytes.
 *
 * <p>Determinism is the point. An operator comparing two reports is asking whether anything
 * changed, and a report carrying a clock reading or a traversal order that depends on how the store
 * happened to be laid out answers "yes" every time — which is the same as not answering. So what is
 * in here is counts and positions, and nothing that varies while the store does not.</p>
 *
 * @param from the bucket the pass started at
 * @param to the bucket the next pass starts at
 * @param examined how many records were looked at
 * @param recordsRemoved how many operation records were removed
 * @param artifactsCollected how many unreferenced artifacts were collected
 * @param bytesReleased how many bytes of capacity were given back
 */
public record SweepReport(long from, long to, long examined, long recordsRemoved,
                          long artifactsCollected, long bytesReleased) {

    /**
     * How this report is written where two of them are compared.
     *
     * @return the report as one line per number, in a fixed order
     */
    public String rendered() {
        return "from=" + from + "\nto=" + to + "\nexamined=" + examined
                + "\nrecords_removed=" + recordsRemoved
                + "\nartifacts_collected=" + artifactsCollected
                + "\nbytes_released=" + bytesReleased + "\n";
    }
}
