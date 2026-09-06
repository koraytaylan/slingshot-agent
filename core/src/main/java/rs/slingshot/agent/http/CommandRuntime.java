// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

/**
 * The packaged command runtime supplied to {@link SubmitServlet} by declarative services.
 *
 * <p>The core bundle owns the transport seam, while a deployment supplies the handler and
 * platform-adapter graph. Keeping this service top-level makes the DS contract visible in the
 * installed descriptor; an absent provider leaves the servlet on its fail-closed empty surface.</p>
 */
public interface CommandRuntime extends SubmitServlet.Commands {
}
