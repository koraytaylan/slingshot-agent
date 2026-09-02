// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

/**
 * Which deployment row this instance matches, and whether this build claims it.
 *
 * <p>Running on a row nobody claimed is not a failure. Plenty of it works, and the whole point of
 * declaring rows rather than inferring them is that a claim is something somebody decided rather
 * than something that happened to be true when the tests last ran. But it is the first thing worth
 * knowing when something does not work, and an operator has no other way to find it out — so it is
 * reported as itself, and not as a failure of whatever noticed it.</p>
 */
public final class DeploymentRowHealthCheck {

    private DeploymentRowHealthCheck() {
    }

    /** Whether this build says it was made for the row this instance matches. */
    public enum Claim {
        /** It does. */
        CLAIMED,
        /** It does not, which is worth knowing and is not a failure. */
        UNCLAIMED
    }

    /** Which row this instance matched, or that none did. */
    public sealed interface Row permits Matched, Unrecognised {
    }

    /**
     * A row this build knows.
     *
     * @param identifier the row's own identifier, as the deployment table spells it
     * @param claim whether this build claims it
     */
    public record Matched(String identifier, Claim claim) implements Row {
    }

    /**
     * A running platform no declared row describes.
     *
     * @param product what the platform calls itself
     * @param version what it says its version is
     */
    public record Unrecognised(String product, String version) implements Row {
    }

    /**
     * What this instance is, as far as the deployment table can say.
     *
     * @param row which row this instance matched
     * @return one result an operator can act on
     */
    public static AgentHealth.Result of(Row row) {
        return switch (row) {
            case final Matched matched when matched.claim() == Claim.CLAIMED ->
                    AgentHealth.healthy(AgentHealth.Check.DEPLOYMENT_ROW, "this instance matches"
                            + " the deployment row " + matched.identifier() + ", and this build claims it");
            case final Matched matched -> AgentHealth.unknown(AgentHealth.Check.DEPLOYMENT_ROW,
                    "this instance matches the deployment row " + matched.identifier() + ", which this"
                            + " build does not claim. Nothing is wrong with that on its own —"
                            + " plenty of this works on a row nobody claimed — but it is the first"
                            + " thing worth knowing when something does not.");
            case final Unrecognised unrecognised -> AgentHealth.unknown(
                    AgentHealth.Check.DEPLOYMENT_ROW, "no declared deployment row describes "
                            + unrecognised.product() + " " + unrecognised.version() + ", so this"
                            + " build cannot say what it was held to here. That is a row somebody"
                            + " has to add and prove, not something this instance can decide.");
        };
    }
}
