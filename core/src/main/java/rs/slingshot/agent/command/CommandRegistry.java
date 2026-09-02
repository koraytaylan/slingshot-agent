// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Stream;

/**
 * Every command this build serves, read from one file each.
 *
 * <p>One file per command rather than one shared list. A shared list is a file every command task
 * has to edit, which turns a footprint rule into a queue and makes sixty independent pieces of work
 * into one sequence — and produces a merge conflict per command on top of that.</p>
 *
 * <p>Presented in wire order however the files were found, so two builds enumerate identically. A
 * registry whose order depended on a directory listing would be a discovery document that differed
 * between two machines running the same commit.</p>
 *
 * <p>An empty directory is an empty registry rather than a failure: a build with no commands is
 * what this product was for its first three plans, and a check that refused one would have been a
 * check nobody could have written then.</p>
 */
public final class CommandRegistry {

    /** Where the committed rows sit, one file per command. */
    public static final String REGISTRY_DIRECTORY = "policy/commands";

    /** What one row's file is called, after the command it declares. */
    public static final String ROW_EXTENSION = ".toml";

    private final List<RegistryRow> rows;

    private CommandRegistry(List<RegistryRow> rows) {
        this.rows = rows;
    }

    /** Why a registry was refused. Each cause is distinct because each has a different fix. */
    public enum Failure {
        /** The directory is not there to read. */
        UNREADABLE,
        /** A row's bytes are not a row at all. */
        UNPARSABLE,
        /** A row states no value for a member every row has. */
        MEMBER_ABSENT,
        /** A row names a class or a requirement this build does not know. */
        MEMBER_UNKNOWN,
        /** Two rows carry the same wire name, so one of them is unreachable. */
        DUPLICATE_WIRE_NAME,
        /** A row's access class and its operation-key requirement contradict each other. */
        DISAGREEING_ROW,
        /** A row runs somewhere this build has no identity answer for. */
        NO_IDENTITY_ANSWER
    }

    /** The result of reading the registry: the registry, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A registry every row of which satisfied every rule.
     *
     * @param registry the loaded registry
     */
    public record Loaded(CommandRegistry registry) implements Outcome {
    }

    /**
     * A read that produced no registry.
     *
     * @param failure why it was refused
     * @param detail what was refused, named so that somebody can fix it
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Reads every row under a directory.
     *
     * @param directory where the rows sit
     * @return the registry, or the one reason it was refused
     */
    public static Outcome read(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new Refused(Failure.UNREADABLE, directory + " is not a directory of rows");
        }
        final List<Path> files = filesUnder(directory);
        final SequencedMap<String, RegistryRow> byName = new LinkedHashMap<>();
        for (final Path file : files) {
            final Outcome refusal = add(byName, file);
            if (refusal instanceof Refused) {
                return refusal;
            }
        }
        return new Loaded(new CommandRegistry(byName.values().stream()
                .sorted(java.util.Comparator.comparing(RegistryRow::wireName))
                .toList()));
    }

    private static Outcome add(SequencedMap<String, RegistryRow> byName, Path file) {
        final Outcome read = row(file);
        if (read instanceof Refused) {
            return read;
        }
        final RegistryRow row = ((Loaded) read).registry().rows().getFirst();
        if (byName.put(row.wireName(), row) != null) {
            return new Refused(Failure.DUPLICATE_WIRE_NAME, row.wireName()
                    + " is declared by more than one file, so one of them is unreachable");
        }
        return read;
    }

    /**
     * Reads one row out of its own file.
     *
     * @param file the row's file
     * @return a registry holding that one row, or the one reason there is none
     */
    public static Outcome row(Path file) {
        final SequencedMap<String, String> stated = stated(text(file));
        final Optional<String> missing = Member.absentIn(stated);
        if (missing.isPresent()) {
            return new Refused(Failure.MEMBER_ABSENT,
                    file.getFileName() + " states no " + missing.get());
        }
        final Optional<AccessClass> access = AccessClass.named(stated.get(Member.ACCESS.spelling));
        final Optional<RegistryRow.OperationKey> key =
                RegistryRow.OperationKey.named(stated.get(Member.OPERATION_KEY.spelling));
        final Optional<ExecutionClass> execution =
                ExecutionClass.named(stated.get(Member.EXECUTION.spelling));
        if (access.isEmpty() || key.isEmpty() || execution.isEmpty()) {
            return new Refused(Failure.MEMBER_UNKNOWN, file.getFileName()
                    + " names a class or a requirement this build does not know");
        }
        if (execution.get() == ExecutionClass.DEFERRED) {
            return new Refused(Failure.NO_IDENTITY_ANSWER, file.getFileName()
                    + " runs later, in a job, and nothing here answers whose identity it would run"
                    + " under; running as the caller is free only inside the caller's own request");
        }
        return built(file, stated, access.get(), key.get(), execution.get());
    }

    private static Outcome built(Path file, SequencedMap<String, String> stated,
                                 AccessClass access, RegistryRow.OperationKey key,
                                 ExecutionClass execution) {
        try {
            final RegistryRow row = new RegistryRow(stated.get(Member.WIRE_NAME.spelling),
                    stated.get(Member.CONTRACT_VERSION.spelling), access, key,
                    Long.parseLong(stated.get(Member.RESULT_BYTES.spelling)),
                    List.of(stated.get(Member.FAILURE_CATEGORIES.spelling).split(",")),
                    stated.get(Member.ARGUMENT_DIGEST.spelling),
                    stated.get(Member.RESULT_DIGEST.spelling),
                    stated.get(Member.LIMITS_DIGEST.spelling),
                    Long.parseLong(stated.get(Member.STAGING_BYTES.spelling)), execution);
            final Optional<String> disagreement = row.disagreement();
            return disagreement.isPresent()
                    ? new Refused(Failure.DISAGREEING_ROW, disagreement.get())
                    : new Loaded(new CommandRegistry(List.of(row)));
        } catch (final IllegalArgumentException incomplete) {
            return new Refused(Failure.UNPARSABLE,
                    file.getFileName() + ": " + incomplete.getMessage());
        }
    }

    /** Every member a row states, and there is no twelfth. */
    private enum Member {
        WIRE_NAME("wire_name"),
        CONTRACT_VERSION("contract_version"),
        ACCESS("access"),
        OPERATION_KEY("operation_key"),
        RESULT_BYTES("result_bytes"),
        FAILURE_CATEGORIES("failure_categories"),
        ARGUMENT_DIGEST("argument_schema_digest"),
        RESULT_DIGEST("result_schema_digest"),
        LIMITS_DIGEST("contract_limits_digest"),
        STAGING_BYTES("staging_bytes"),
        EXECUTION("execution");

        private final String spelling;

        Member(String spelling) {
            this.spelling = spelling;
        }

        private static Optional<String> absentIn(SequencedMap<String, String> stated) {
            return java.util.Arrays.stream(values())
                    .map(member -> member.spelling)
                    .filter(spelling -> !stated.containsKey(spelling)
                            || stated.get(spelling).isBlank())
                    .findFirst();
        }
    }

    /**
     * Every row this build serves, in wire order.
     *
     * @return the rows
     */
    public List<RegistryRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * The row one wire name declares.
     *
     * @param wireName the name a caller submits
     * @return the row, or nothing where this build serves no such command
     */
    public Optional<RegistryRow> row(String wireName) {
        return rows.stream().filter(row -> row.wireName().equals(wireName)).findFirst();
    }

    /**
     * Every wire name this build serves, in wire order.
     *
     * @return the names
     */
    public List<String> wireNames() {
        return rows.stream().map(RegistryRow::wireName).toList();
    }

    private static SequencedMap<String, String> stated(String document) {
        final SequencedMap<String, String> stated = new LinkedHashMap<>();
        document.lines()
                .map(CommandRegistry::withoutComment)
                .filter(line -> line.indexOf('=') > 0)
                .forEach(line -> stated.put(line.substring(0, line.indexOf('=')).strip(),
                        unquote(line.substring(line.indexOf('=') + 1))));
        return stated;
    }

    private static String withoutComment(String line) {
        final String stripped = line.strip();
        return stripped.startsWith("#") ? "" : stripped;
    }

    private static String unquote(String value) {
        final String stripped = value.strip();
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            return stripped.substring(1, stripped.length() - 1)
                    .replace("\"", "").replace(" ", "");
        }
        return stripped.startsWith("\"") && stripped.endsWith("\"") && stripped.length() > 1
                ? stripped.substring(1, stripped.length() - 1)
                : stripped;
    }

    private static List<Path> filesUnder(Path directory) {
        try (Stream<Path> found = Files.list(directory)) {
            return found.filter(file -> file.toString().endsWith(ROW_EXTENSION))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(directory + " could not be read", unreadable);
        }
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " could not be read", unreadable);
        }
    }

    /**
     * The one reason a registry was refused, where it was.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where there is a registry
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }

    /**
     * Every row, ordered and collected from several registries.
     *
     * @param registries the registries
     * @return one registry holding all their rows in wire order
     */
    public static CommandRegistry of(List<CommandRegistry> registries) {
        final List<RegistryRow> all = new ArrayList<>();
        registries.forEach(registry -> all.addAll(registry.rows()));
        return new CommandRegistry(all.stream()
                .sorted(java.util.Comparator.comparing(RegistryRow::wireName))
                .toList());
    }
}
