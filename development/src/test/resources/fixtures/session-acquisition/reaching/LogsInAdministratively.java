// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.handler;

/** A fixture handler that reaches for something it was not given. */
final class LogsInAdministratively {

    void reach() {
        repository.loginAdministrative(null);
    }
}
