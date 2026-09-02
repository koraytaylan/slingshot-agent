// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.nio.file.Path;

/**
 * A container this harness started, and the only way to reach it again.
 *
 * <p>Everything about a started container is reached through the handle that started it, and
 * nothing looks a container up by name and acts on what it finds. That matters for one specific
 * reason: a name can be taken by a replacement, and a cleanup that looked one up would then stop a
 * container it never started — on somebody's machine, in the middle of their work, for a reason
 * they could not possibly connect to this suite.</p>
 *
 * @param identifier the exact container the engine started, as the engine names it
 * @param image the image it was started from, at the digest it was pinned to
 * @param capturedOutput where its output is being written
 * @param mappedPort the port on this machine that reaches it
 */
public record ContainerHandle(String identifier, String image, Path capturedOutput, int mappedPort) {

    /**
     * Holds a handle to a container that exists.
     *
     * @throws IllegalArgumentException if the identifier is blank, because a handle to nothing is a
     *     handle that would later stop something else
     */
    public ContainerHandle {
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("a handle names no container");
        }
    }

    /**
     * Where on this machine the container answers.
     *
     * @return the address, as a caller would reach it
     */
    public String address() {
        return "http://localhost:" + mappedPort;
    }
}
