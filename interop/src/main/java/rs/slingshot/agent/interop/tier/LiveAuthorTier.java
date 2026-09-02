// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One real author, asked only what the registry says replaces nothing, only when somebody asks.
 *
 * <p>One run against one author is evidence about that author and about nothing else. Saying so in
 * the report is what stops it being quoted later as evidence about a deployment row — and the
 * acceptance matrix refuses to take it as one, so it cannot become one by accident.</p>
 *
 * <p>What runs is derived from the registry rather than listed: exactly the rows classified as
 * replacing nothing. A command reclassified later changes what runs without anybody editing this,
 * which is the difference between a set and a list somebody has to remember to update.</p>
 */
public final class LiveAuthorTier {

    /** Where the one instance a live run may reach is named. */
    public static final String LIVE_FILE = "support/live-author.toml";

    /** Where one row per command is declared. */
    public static final String REGISTRY = "policy/commands";

    /** What has to be typed before this reaches anything. */
    public static final String ENABLING_FLAG = "--yes-run-against-a-real-author";

    /** What the report says it is, so nobody quotes it as something else. */
    public static final String WHAT_THIS_IS = "an observation of one unattested instance";

    private LiveAuthorTier() {
    }

    /** Whether a live run may happen at all. */
    public sealed interface Permission permits Permitted, Refused {
    }

    /**
     * A run somebody asked for against an instance somebody acknowledged.
     *
     * @param address where the instance is
     * @param identity who the run acts as
     */
    public record Permitted(String address, String identity) implements Permission {
    }

    /**
     * A run that may not happen, and why.
     *
     * @param detail what is missing, said as something somebody can act on
     */
    public record Refused(String detail) implements Permission {
    }

    /**
     * Whether this run may reach anything.
     *
     * <p>The flag is checked before the configuration is read, deliberately. A command that parsed
     * a configuration and then decided whether it was allowed to would be a command that had
     * already done something on the way to deciding.</p>
     *
     * @param root the repository root
     * @param arguments what the command was invoked with
     * @return whether it may, or the one reason it may not
     */
    public static Permission permission(Path root, List<String> arguments) {
        if (!arguments.equals(List.of(ENABLING_FLAG))) {
            return new Refused("this reaches a real author instance and does not do so by default;"
                    + " run it with " + ENABLING_FLAG);
        }
        final Path live = root.resolve(LIVE_FILE);
        if (!Files.isRegularFile(live)) {
            return new Refused("no instance is named at all");
        }
        final String held = read(live);
        if (!held.lines().map(String::strip).anyMatch("acknowledged = true"::equals)) {
            return new Refused("no instance is acknowledged; an operator has to set `address` to"
                    + " their instance and `acknowledged` to true after reading what will be run"
                    + " against it, because an address is not permission");
        }
        return new Permitted(valueOf(held, "address"), valueOf(held, "identity"));
    }

    /**
     * Exactly the commands a live run may issue, derived from the registry.
     *
     * @param root the repository root
     * @return the wire names, in the registry's own order
     */
    public static List<String> commandsThatReplaceNothing(Path root) {
        final List<String> commands = new ArrayList<>();
        try (var rows = Files.list(root.resolve(REGISTRY))) {
            rows.filter(row -> String.valueOf(row.getFileName()).endsWith(".toml"))
                    .sorted()
                    .filter(row -> read(row).contains("access = \"read\""))
                    .forEach(row -> commands.add(
                            String.valueOf(row.getFileName()).replace(".toml", "")));
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
        return List.copyOf(commands);
    }

    /**
     * What a run reports, labelled as what it is.
     *
     * @param instanceAddress where it ran
     * @param platformVersion what that instance says it is
     * @param commands what was run against it
     * @return the report
     */
    public static String report(String instanceAddress, String platformVersion,
                                List<String> commands) {
        return WHAT_THIS_IS + " at " + instanceAddress + " running " + platformVersion
                + ", which ran " + commands.size() + " commands that replace nothing: " + commands
                + ". It is not evidence about a deployment row.";
    }

    private static String valueOf(String document, String key) {
        return document.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(key + " = \""))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElse("");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
