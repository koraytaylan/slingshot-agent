// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.handler;

/**
 * A fixture handler that explains what it may not do rather than doing it.
 *
 * <p>It may not call loginService, loginAdministrative, or getServiceResourceResolver; it may not
 * hold a BundleContext, reach FrameworkUtil, or take a @Reference. Every one of those forms is
 * named here, in prose, which is explaining the rule rather than breaking it.</p>
 */
final class ExplainsWhatItMayNotDo {

    void work() {
        // Nothing here calls loginService or loginAdministrative, holds a BundleContext, reaches
        // FrameworkUtil, takes a @Reference, or asks for getServiceResourceResolver.
        return;
    }
}
