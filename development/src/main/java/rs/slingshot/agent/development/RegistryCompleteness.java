// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether this repository's registry and the client's published table are the same sixty-four rows.
 *
 * <p>A command one side holds and the other does not is a refused submission in production, and
 * this is the cheapest possible place to find that. It only becomes checkable once the last row
 * exists, which is why it sits at the end of the plan that writes them.</p>
 *
 * <p>Compared field by field rather than row by row, because the fixes differ. A row whose access
 * class disagrees is a permission decision somebody has to make; one whose key requirement
 * disagrees is a resend that will have a second effect; one whose result bound disagrees is an
 * answer one half will refuse. Reporting them together would hand somebody three problems wearing
 * one label.</p>
 */
public final class RegistryCompleteness {

    private RegistryCompleteness() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "registry-completeness";

    /** Where the registry declares one file per command. */
    public static final String REGISTRY_DIRECTORY = "policy/commands";

    /** How many commands both halves of this protocol have. */
    public static final int SIXTYFOUR_COMMANDS = 64;

    /** A command this registry declares and the client does not publish. */
    public static final String UNPUBLISHED = "command-the-client-does-not-publish";

    /** A command the client publishes and this registry does not declare. */
    public static final String UNIMPLEMENTED = "command-this-registry-does-not-declare";

    /** A row whose access class is not the client's. */
    public static final String ACCESS_DISAGREES = "access-class-disagrees";

    /** A row whose operation-key requirement is not the client's. */
    public static final String KEY_DISAGREES = "operation-key-disagrees";

    /** A row whose result bound is not the client's. */
    public static final String BOUND_DISAGREES = "result-bound-disagrees";

    /** A registry that is not exactly sixty-four rows. */
    public static final String WRONG_COUNT = "registry-is-not-sixty-four-rows";

    /** Two rows a caller could confuse for one another. */
    public static final String IDENTITY_COLLIDES = "contract-identity-collides";

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param registry the directory one file per command sits in
     * @param root the repository root, which is where the client's published documents are read
     *     from
     */
    public record Sources(Path registry, Path root) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(REGISTRY_DIRECTORY), root);
        }

        /**
         * The same sources with the registry read from somewhere else.
         *
         * @param elsewhere where the registry sits instead
         * @return the sources
         */
        public Sources withRegistry(Path elsewhere) {
            return new Sources(elsewhere, root);
        }

        /**
         * The same sources with the client's published documents read from somewhere else.
         *
         * @param elsewhere where a repository holding those documents sits instead
         * @return the sources
         */
        public Sources withClient(Path elsewhere) {
            return new Sources(registry, elsewhere);
        }
    }

    /**
     * What one row says about itself, in the three fields both halves have to agree on.
     *
     * @param access whether the command reads or writes
     * @param operationKey whether it requires a key, refuses one, or permits one
     * @param resultBytes the most one answer may carry
     */
    public record Row(String access, String operationKey, long resultBytes) {
    }

    /**
     * Whether the two tables are the same sixty-four rows, field for field.
     *
     * @param sources everywhere to read from
     * @return one finding per disagreement, each naming what it is about
     */
    public static PolicyReport against(Sources sources) {
        final SequencedMap<String, Row> declared = rowsIn(sources.registry());
        final SequencedMap<String, Row> published = publishedRows(sources.root());
        final List<PolicyFinding> findings = new ArrayList<>(countFindings(declared));
        findings.addAll(sideFindings(declared, published));
        findings.addAll(fieldFindings(declared, published));
        findings.addAll(identityFindings(sources.registry()));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> countFindings(SequencedMap<String, Row> declared) {
        return declared.size() == SIXTYFOUR_COMMANDS
                ? List.of()
                : List.of(PolicyFinding.inFile(REGISTRY_DIRECTORY, WRONG_COUNT, declared.size()
                        + " rows, and both halves of this protocol have " + SIXTYFOUR_COMMANDS));
    }

    private static List<PolicyFinding> sideFindings(SequencedMap<String, Row> declared,
                                                    SequencedMap<String, Row> published) {
        final List<PolicyFinding> findings = new ArrayList<>();
        declared.keySet().stream()
                .filter(command -> !published.containsKey(command))
                .forEach(command -> findings.add(PolicyFinding.inFile(
                        REGISTRY_DIRECTORY + "/" + command + ".toml", UNPUBLISHED, command
                                + " is declared here and the client publishes nothing by that"
                                + " name, so nothing would ever submit it")));
        published.keySet().stream()
                .filter(command -> !declared.containsKey(command))
                .forEach(command -> findings.add(PolicyFinding.inFile(REGISTRY_DIRECTORY,
                        UNIMPLEMENTED, command + " is published by the client and declared by no"
                                + " row here, so a submission of it would be refused as unknown")));
        return findings;
    }

    private static List<PolicyFinding> fieldFindings(SequencedMap<String, Row> declared,
                                                     SequencedMap<String, Row> published) {
        final List<PolicyFinding> findings = new ArrayList<>();
        declared.forEach((command, row) -> {
            final Row theirs = published.get(command);
            if (theirs == null) {
                return;
            }
            final String file = REGISTRY_DIRECTORY + "/" + command + ".toml";
            if (!row.access().equals(theirs.access())) {
                findings.add(PolicyFinding.inFile(file, ACCESS_DISAGREES, command + " is "
                        + row.access() + " here and " + theirs.access() + " to the client"));
            }
            if (!row.operationKey().equals(theirs.operationKey())) {
                findings.add(PolicyFinding.inFile(file, KEY_DISAGREES, command + " " + row
                        .operationKey() + " an operation key here and " + theirs.operationKey()
                        + " one to the client, so a resend would have a second effect on one side"));
            }
            if (row.resultBytes() != theirs.resultBytes()) {
                findings.add(PolicyFinding.inFile(file, BOUND_DISAGREES, command + " answers up to "
                        + row.resultBytes() + " bytes here and " + theirs.resultBytes()
                        + " to the client, so one half would refuse an answer the other sent"));
            }
        });
        return findings;
    }

    private static List<PolicyFinding> identityFindings(Path registry) {
        final SequencedMap<String, String> identities = new LinkedHashMap<>();
        final List<PolicyFinding> findings = new ArrayList<>();
        rowFiles(registry).forEach(file -> {
            final String command = commandOf(file);
            final String identity = identityOf(read(file));
            final String held = identities.putIfAbsent(identity, command);
            if (held != null) {
                findings.add(PolicyFinding.inFile(REGISTRY_DIRECTORY + "/" + command + ".toml",
                        IDENTITY_COLLIDES, command + " and " + held + " have the same contract"
                                + " identity, so a submission of one could be answered as the"
                                + " other"));
            }
        });
        return findings;
    }

    /**
     * The three agreed fields of every row this registry declares, in ascending wire-name order.
     *
     * <p>Sorted here rather than taken in the order a directory happens to hand them over, because
     * a listing's order is the file system's business and the answer must not be.</p>
     *
     * @param registry the registry directory
     * @return the rows by command name
     */
    public static SequencedMap<String, Row> rowsIn(Path registry) {
        final SequencedMap<String, Row> rows = new LinkedHashMap<>();
        rowFiles(registry).forEach(file -> {
            final String held = read(file);
            rows.put(commandOf(file), new Row(valueOf(held, "access"),
                    valueOf(held, "operation_key"), numberOf(held)));
        });
        return rows;
    }

    /**
     * The same three fields of every command the client publishes.
     *
     * <p>Read from the client's own published documents rather than from a table kept beside them.
     * Two copies of the client's table would be two things to keep current, and the one nobody
     * remembered would be the one this gate believed.</p>
     *
     * @param root the repository root
     * @return the rows by command name
     */
    public static SequencedMap<String, Row> publishedRows(Path root) {
        final String catalogue = read(root.resolve(CommandConformance.CLIENT_CATALOG));
        final String classification =
                read(root.resolve(CommandConformance.CLIENT_CLASSIFICATION));
        final SequencedMap<String, Long> bounds = new LinkedHashMap<>();
        final Matcher entries = Pattern.compile("\"wire_name\":\"([a-z0-9_]+)\"")
                .matcher(catalogue);
        int from = 0;
        while (entries.find()) {
            // An entry runs from the end of the previous one to its own wire name, which the
            // client publishes last. Looking backwards for the nearest brace instead would find
            // the one opening a nested artifact slot, and answer about that.
            bounds.put(entries.group(1), boundIn(catalogue.substring(from, entries.start())));
            from = entries.end();
        }
        final SequencedMap<String, Row> rows = new LinkedHashMap<>();
        bounds.keySet().stream().sorted().forEach(command -> rows.put(command, new Row(
                memberOf(classification, command, "access"),
                memberOf(classification, command, "operation_key"),
                bounds.get(command))));
        return rows;
    }

    private static String memberOf(String classification, String command, String member) {
        final Matcher held = Pattern.compile("\"" + command + "\":\\{[^}]*\"" + member
                        + "\":\"([a-z_]+)\"")
                .matcher(classification);
        return held.find() ? held.group(1) : "";
    }

    private static long boundIn(String entry) {
        final Matcher bound = Pattern.compile("\"maximum_result_bytes\":(\\d+)").matcher(entry);
        long held = 0;
        while (bound.find()) {
            held = Long.parseLong(bound.group(1));
        }
        return held;
    }

    private static String valueOf(String row, String key) {
        final Matcher held = Pattern.compile("(?m)^" + key + " = \"([a-z_]+)\"$").matcher(row);
        return held.find() ? held.group(1) : "";
    }

    private static long numberOf(String row) {
        final Matcher held = Pattern.compile("(?m)^result_bytes = (\\d+)$").matcher(row);
        return held.find() ? Long.parseLong(held.group(1)) : 0;
    }

    /**
     * The five-field contract identity one row derives, rendered so two can be compared.
     *
     * @param row the row's own text
     * @return the identity
     */
    public static String identityOf(String row) {
        return String.join("|", valueOf(row, "wire_name"), valueOf(row, "contract_version"),
                digestOf(row, "argument_schema_digest"), digestOf(row, "result_schema_digest"),
                digestOf(row, "contract_limits_digest"));
    }

    private static String digestOf(String row, String key) {
        final Matcher held = Pattern.compile("(?m)^" + key + " = \"([0-9a-f]+)\"$").matcher(row);
        return held.find() ? held.group(1) : "";
    }

    private static List<Path> rowFiles(Path registry) {
        if (!Files.isDirectory(registry)) {
            return List.of();
        }
        try (Stream<Path> held = Files.list(registry)) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .sorted(java.util.Comparator.comparing(RegistryCompleteness::commandOf))
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String commandOf(Path file) {
        final String name = String.valueOf(file.getFileName());
        return name.substring(0, name.length() - ".toml".length());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Whether the rows come back in ascending wire-name order however the directory enumerates.
     *
     * @param registry the registry directory
     * @return whether they do
     */
    public static boolean isAscending(Path registry) {
        final List<String> names = List.copyOf(rowsIn(registry).keySet());
        return names.equals(names.stream().sorted().toList());
    }

    /**
     * Whether the client's own published version string for a command is the one this row states.
     *
     * @param root the repository root
     * @param command the command's wire name
     * @return the version, or nothing where the client publishes none
     */
    public static Optional<String> publishedVersion(Path root, String command) {
        final Matcher held = Pattern.compile("\"" + command + "\":\"([0-9.]+)\"")
                .matcher(read(root.resolve(CommandConformance.CLIENT_TABLE)));
        return held.find() ? Optional.of(held.group(1)) : Optional.empty();
    }
}
