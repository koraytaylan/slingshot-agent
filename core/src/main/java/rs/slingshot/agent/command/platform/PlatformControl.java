// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What this deployment actually permits, asked before a control command does anything at all.
 *
 * <p>This is the second of two separate questions, and keeping them separate is the point. Whether
 * <em>this caller</em> may do something is decided by the group they are in, and it is decided the
 * same way for every command in this product. Whether <em>this deployment</em> permits it at all is
 * decided here, and it has nothing to do with who is asking: on an environment whose configuration
 * is immutable, nobody can change a configuration, and the administrator is refused exactly as
 * firmly as everybody else.</p>
 *
 * <p>Refusing is the whole feature. A change written through a running platform that does not
 * persist it is worse than a refusal, because the platform accepts it, the answer says it worked,
 * and the change is gone by the next deployment — at which point the operator has already built
 * three things on top of believing it. The refusal names the deployment row, so the answer says
 * <em>where</em> rather than only <em>no</em>.</p>
 */
public final class PlatformControl {

    /** The category a control this deployment does not provide is refused under. */
    public static final String NOT_PERMITTED = "deployment_does_not_permit";

    private final String deployment;
    private final Set<ControlCapability> provided;

    private PlatformControl(String deployment, Set<ControlCapability> provided) {
        this.deployment = deployment;
        this.provided = Collections.unmodifiableSet(new LinkedHashSet<>(provided));
    }

    /**
     * The controls one deployment provides.
     *
     * @param deployment the deployment row's own identifier, which a refusal names
     * @param provided what that row says it provides
     * @return the gate
     */
    public static PlatformControl of(String deployment, Set<ControlCapability> provided) {
        return new PlatformControl(deployment, provided);
    }

    /**
     * Which deployment this is about.
     *
     * @return its identifier
     */
    public String deployment() {
        return deployment;
    }

    /**
     * What this deployment provides.
     *
     * @return the capabilities, which nothing may add to
     */
    public Set<ControlCapability> provided() {
        return provided;
    }

    /** Whether one control may proceed here. */
    public sealed interface Verdict permits Permitted, Refused {
    }

    /** It may. */
    public record Permitted() implements Verdict {
    }

    /**
     * It may not, and the answer says where as well as no.
     *
     * @param category the declared category this is reported under
     * @param detail what to tell the caller, naming the deployment and what it does instead
     */
    public record Refused(String category, String detail) implements Verdict {
    }

    /**
     * Whether this deployment permits one control.
     *
     * @param capability what the command is about to do
     * @return that it may, or the refusal to answer with instead
     */
    public Verdict permits(ControlCapability capability) {
        return provided.contains(capability)
                ? new Permitted()
                : new Refused(NOT_PERMITTED, capability.spelling() + " is not something "
                        + deployment + " provides. It is refused here rather than performed"
                        + " against the running platform, because a change that deployment does"
                        + " not keep would be accepted, reported as done, and gone by the next"
                        + " release — and by then you would have built on believing it.");
    }

    /**
     * Every capability, in the order the closed set declares them.
     *
     * @return the capabilities
     */
    public static List<ControlCapability> everyCapability() {
        return List.of(ControlCapability.values());
    }
}
