// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Engine responses cannot turn a restart into an invented usable address. */
final class CrashInjectorTest {

    @TempDir
    Path directory;

    @Test
    void aRestartReadsTheNewPortAndPreservesTheContainerIdentity() throws IOException {
        final ContainerHandle before = handle();
        final ContainerHandle after = injector("printf '127.0.0.1:8181\\n'")
                .restarted(before, 8080).orElseThrow();
        assertEquals(before.identifier(), after.identifier());
        assertEquals(before.image(), after.image());
        assertEquals(before.capturedOutput(), after.capturedOutput());
        assertEquals(8181, after.mappedPort());
        assertEquals("start retained\ninspect --format {{.State.Running}} retained\n"
                        + "port retained 8080/tcp\n",
                Files.readString(directory.resolve("calls")));
    }

    @Test
    void anUnpublishedPortRemainsAbsent() throws IOException {
        assertEquals(Optional.empty(), injector("printf 'unpublished\\n'")
                .restarted(handle(), 8080));
    }

    @Test
    void aMalformedPortRemainsAbsent() throws IOException {
        assertEquals(Optional.empty(), injector("printf '127.0.0.1:not-a-port\\n'")
                .restarted(handle(), 8080));
    }

    @Test
    void anEngineRefusalCannotSupplyAnAddressEvenWithPlausibleOutput() throws IOException {
        assertEquals(Optional.empty(), injector("printf '127.0.0.1:8181\\n'; exit 7")
                .restarted(handle(), 8080));
    }

    private ContainerHandle handle() {
        return new ContainerHandle("retained", "fixture-image", directory.resolve("output"), 8080);
    }

    private CrashInjector injector(String portAnswer) throws IOException {
        final Path engine = directory.resolve("engine");
        Files.writeString(engine, """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$(dirname "$0")/calls"
                case "$1" in
                  start) exit 0 ;;
                  inspect) printf 'true\\n' ;;
                  port) @PORT_ANSWER@ ;;
                  *) exit 1 ;;
                esac
                """.replace("@PORT_ANSWER@", portAnswer));
        Files.setPosixFilePermissions(engine, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        final Path repository = Path.of(System.getProperty("slingshot.repository.root"));
        final String values = Files.readString(repository.resolve("support/interop-harness.toml"))
                .replace("executable = \"podman\"", "executable = \"" + engine + "\"");
        final Path configuration = directory.resolve("harness.toml");
        Files.writeString(configuration, values);
        return CrashInjector.alongside(ContainerHarness.from(configuration));
    }
}
