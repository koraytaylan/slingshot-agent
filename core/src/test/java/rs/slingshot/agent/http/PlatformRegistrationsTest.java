// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The platform commands this bundle registers, and the deployment gate they are registered under.
 *
 * <p>The gate is written down twice - once in the deployment matrix every check reads, once here
 * where the running bundle needs it - so this compares the two rather than trusting that they were
 * copied correctly.</p>
 */
final class PlatformRegistrationsTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    @Test
    @DisplayName("the gate provides exactly what the built-for row of the deployment matrix provides")
    void thegateIsTheBuiltForRow() throws IOException {
        final List<String> lines = Files.readAllLines(repositoryRoot()
                .resolve("support/deployments.toml"));
        final Set<String> provided = new LinkedHashSet<>();
        boolean inRow = false;
        String capability = "";
        for (final String line : lines) {
            final String held = line.trim();
            if (held.startsWith("id = ")) {
                inRow = ("id = \"" + PlatformRegistrations.BUILT_FOR + "\"").equals(held);
            } else if (inRow && held.startsWith("capability = ")) {
                capability = held.substring(held.indexOf('"') + 1, held.lastIndexOf('"'));
            } else if (inRow && "provided = true".equals(held)) {
                provided.add(capability);
            }
        }
        assertTrue(lines.stream().anyMatch(line -> ("id = \"" + PlatformRegistrations.BUILT_FOR
                        + "\"").equals(line.trim())),
                "the deployment matrix has no row for the deployment this build is made for");
        assertEquals(provided, new LinkedHashSet<>(PlatformRegistrations.deploymentControl()
                .provided().stream().map(ControlCapability::spelling).toList()));
    }

    @Test
    @DisplayName("every platform command registered is a committed registry row")
    void everyRegistrationIsARegistryRow() {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read()).registry();
        final List<String> registered = PlatformRegistrations.registrations(CONTRACT).stream()
                .map(CommandDispatch.Registration::wireName).toList();
        assertInstanceOf(CommandRegistry.Loaded.class, registry.active(registered),
                "a platform registration names a command the registry does not hold");
        assertEquals(registered.size(), Set.copyOf(registered).size(),
                "a platform command is registered twice");
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "test is not inside repository");
    }
}
