// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

/**
 * Where one run gets its own view of the platform's configurations.
 *
 * <p>A handler holds this rather than a catalogue, for the same reason the one command that needs
 * somewhere to work holds a room-opener rather than a room: a view onto a platform service is
 * something a run has, not something a handler owns. Holding the service itself would mean every
 * run through that handler shared whatever state the implementation kept — which is fine today,
 * because none of them keeps any, and is exactly the kind of thing that stops being fine without
 * anybody noticing.</p>
 */
@FunctionalInterface
public interface ConfigurationCatalogues {

    /**
     * A view for one run.
     *
     * @return the catalogue
     */
    ConfigurationCatalogue open();
}
