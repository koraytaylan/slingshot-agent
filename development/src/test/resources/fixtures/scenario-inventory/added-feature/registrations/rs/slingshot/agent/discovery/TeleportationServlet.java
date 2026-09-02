// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.discovery;

/** A fixture servlet, which is never compiled and never run. */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/capabilities",
        "sling.servlet.paths=/bin/slingshot/agent/teleportation",
        "sling.servlet.methods=GET"
})
final class TeleportationServlet {
}
