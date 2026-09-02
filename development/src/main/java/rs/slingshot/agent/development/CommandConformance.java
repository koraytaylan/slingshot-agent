// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What makes a command exist, checked once so the sixty-fourth is as complete as the first.
 *
 * <p>A command is not a handler. A handler that answers correctly and has no committed schema, no
 * declared failure set, no vectors at its bounds, and no interop scenario is a command in the sense
 * that it runs and in no other sense: nobody can tell what it accepts, a caller cannot know what it
 * may be told, and nothing would notice if it stopped working. So existence is six facts and this
 * checks all six, because the one that gets left for later is always the same one — the interop
 * scenario, which is the only one whose absence nothing else reveals.</p>
 *
 * <h2>Every failure at once</h2>
 *
 * <p>Findings are reported together rather than one at a time. Somebody adding a command wants the
 * whole list of what it still needs, not to run the gate six times discovering one more each time,
 * and a checker that stops at the first fact makes adding a command an exercise in guessing how
 * much is left.</p>
 */
public final class CommandConformance {

    /** Where the registry's own files are, one per command. */
    public static final String REGISTRY_DIRECTORY = "policy/commands";

    /** Where the schemas each command is held to are committed. */
    public static final String SCHEMA_DIRECTORY = "schemas/agent-protocol/command";

    /** Where the conformance vectors are committed. */
    public static final String VECTORS_FILE = "schemas/agent-protocol-vectors.json";

    /** Where the interop scenarios are declared, one file per scenario. */
    public static final String SCENARIO_DIRECTORY = "interop/scenarios";

    /**
     * Where the client's own published command table is carried.
     *
     * <p>The sibling's own document, committed here once. It is the same copy the contract suite
     * compares this side's bounds against, rather than a second one taken for this check: two
     * copies of the client's table would be two things to keep current, and the one nobody
     * remembered would be the one this gate believed.</p>
     */
    public static final String CLIENT_TABLE =
            "core/src/test/resources/fixtures/agent-contract/sibling-command-contract.json";

    /** The member of the client's table that lists every command it knows. */
    public static final String CLIENT_TABLE_MEMBER = "command_semantic_contract_versions";

    /**
     * Where the client's own classification of each command is carried.
     *
     * <p>Whether a command takes an operation key follows from whether it is intrinsically
     * idempotent, and that is the client's judgement rather than something derivable here. It is
     * emphatically not derivable from the access class: the client publishes twenty-six reads that
     * refuse a key beside two that require one, because reading a repository twice is not one
     * operation when the repository can change in between. So the row's own answer is compared with
     * the client's rather than re-derived from something that does not imply it.</p>
     */
    public static final String CLIENT_CLASSIFICATION =
            "core/src/test/resources/fixtures/agent-contract/sibling-command-classification.json";

    /**
     * Where the client's own published schema manifest is carried.
     *
     * <p>The client generates both role schemas for all sixty-four commands from its own types and
     * publishes their digests. Its own module says what those digests are for: "compatibility is a
     * comparison of digests". So the schema this side commits is not this side's to write — it is
     * the client's document, mirrored, and a digest that differs is not a formatting difference but
     * two halves of one protocol describing different messages.</p>
     *
     * <p>This is the check whose absence let fourteen commands be written against member names
     * nobody on the other end sends. A schema that only has to agree with the row that names it
     * agrees with itself, which is the one thing it cannot usefully prove.</p>
     */
    public static final String CLIENT_SCHEMAS =
            "core/src/test/resources/fixtures/agent-contract/sibling-command-schemas.json";

    /** The two roles every command's schemas are published under, spelled as the client spells. */
    public static final List<String> SCHEMA_ROLES = List.of("arguments", "result");

    /**
     * Where the client's own published catalog is carried.
     *
     * <p>The client publishes, for every command, the ways it may fail and the most a result may
     * carry. Those are not this side's to choose either: a category a caller is never told about is
     * a failure they cannot handle, and a bound the two halves disagree about is a result one of
     * them refuses.</p>
     */
    public static final String CLIENT_CATALOG =
            "core/src/test/resources/fixtures/agent-contract/sibling-command-catalog.json";

    private CommandConformance() {
    }

    /**
     * One of the six things that has to be true for a command to exist.
     *
     * <p>Each is a separate rule because each has a different thing for somebody to do about it,
     * and a single "incomplete" finding would tell them only that something is missing.</p>
     */
    public enum Fact {
        /** A committed argument schema whose digest is the one the row states. */
        ARGUMENT_SCHEMA("command-argument-schema"),
        /** A committed result schema whose digest is the one the row states. */
        RESULT_SCHEMA("command-result-schema"),
        /** A typed model agreeing with those schemas in both directions. */
        TYPED_MODEL("command-typed-model"),
        /** A declared failure set equal to what the handler can produce, both ways. */
        FAILURE_SET("command-failure-set"),
        /** A vector at and one past every bound the command declares. */
        BOUND_VECTORS("command-bound-vectors"),
        /** An interop scenario naming it, on a tier that can run it. */
        INTEROP_SCENARIO("command-interop-scenario"),
        /** A key requirement and access class equal to the client's own classification. */
        CLIENT_CLASSIFICATION_AGREES("command-classification-disagrees"),
        /** Both committed schemas byte-identical to the client's published ones. */
        CLIENT_SCHEMA_AGREES("command-schema-disagrees"),
        /** A failure set and a result bound equal to the client's published catalog. */
        CLIENT_CATALOG_AGREES("command-catalog-disagrees");

        private final String rule;

        Fact(String rule) {
            this.rule = rule;
        }

        /**
         * How this fact's absence is spelled in a finding.
         *
         * @return the rule identifier
         */
        public String rule() {
            return rule;
        }
    }

    /** How a command this side holds and the client does not, or the reverse, is reported. */
    public enum Divergence {
        /** This registry holds a command the client's published table does not. */
        NOT_IN_THE_CLIENT_TABLE("command-not-in-the-client-table"),
        /** The client's published table holds a command this registry does not. */
        NOT_IN_THIS_REGISTRY("command-not-in-this-registry");

        private final String rule;

        Divergence(String rule) {
            this.rule = rule;
        }

        /**
         * How this divergence is spelled in a finding.
         *
         * @return the rule identifier
         */
        public String rule() {
            return rule;
        }
    }

    /**
     * Every way this repository's commands fall short, reported together.
     *
     * @param root the repository root
     * @return the findings, in their own deterministic order, and empty where every command exists
     */
    public static List<PolicyFinding> against(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final Set<String> declared = registeredCommands(root);
        for (final String command : declared) {
            findings.addAll(factsOf(root, command));
        }
        divergences(declared, publishedCommands(root)).stream()
                .filter(finding -> !Divergence.NOT_IN_THIS_REGISTRY.rule().equals(finding.rule()))
                .forEach(findings::add);
        return findings.stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    /**
     * The commands the client publishes and this build has not written yet.
     *
     * <p>Kept out of {@link #against} deliberately. The two directions of divergence are not the
     * same kind of thing: a command this side serves and the client does not know is a defect now,
     * because a caller can reach something no client will ever ask for and nothing else would say
     * so. A command the client publishes and this side has not written is simply unwritten — the
     * ordinary state of every command until the plan that adds it — and a gate that failed on it
     * would be red from the first command to the sixty-fourth, which is a gate nobody reads and
     * therefore a gate that catches nothing.</p>
     *
     * <p>It is still reported, because "how many are left" is worth knowing and worth being unable
     * to lose track of. It is a count, not a failure.</p>
     *
     * @param root the repository root
     * @return one finding per command the client publishes and this registry does not declare
     */
    public static List<PolicyFinding> unimplemented(Path root) {
        return divergences(registeredCommands(root), publishedCommands(root)).stream()
                .filter(finding -> Divergence.NOT_IN_THIS_REGISTRY.rule().equals(finding.rule()))
                .sorted()
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Which commands this registry declares, read from the directory rather than from any list.
     *
     * <p>Read from the directory on purpose. A list written into this checker would have to be
     * edited alongside every command, which is the shared file the registry exists to avoid — and a
     * command somebody forgot to add to it would pass the gate by being invisible to it.</p>
     *
     * @param root the repository root
     * @return the wire names, in wire order
     */
    public static Set<String> registeredCommands(Path root) {
        final Path directory = root.resolve(REGISTRY_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .map(file -> String.valueOf(file.getFileName()))
                    .map(name -> name.substring(0, name.length() - ".toml".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(directory + " is not readable", unreadable);
        }
    }

    /**
     * Which commands the client's own published table names.
     *
     * @param root the repository root
     * @return the wire names, in wire order
     */
    public static Set<String> publishedCommands(Path root) {
        final String table = read(root.resolve(CLIENT_TABLE));
        final int start = table.indexOf("\"" + CLIENT_TABLE_MEMBER + "\":{");
        if (start < 0) {
            return Set.of();
        }
        final int open = table.indexOf('{', start);
        final int close = table.indexOf('}', open);
        return java.util.regex.Pattern.compile("\"([a-z0-9_]+)\":\"")
                .matcher(table.substring(open, close))
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Which of the six facts one command is missing.
     *
     * @param root the repository root
     * @param command the command's wire name
     * @return one finding per missing fact, and none where all six hold
     */
    public static List<PolicyFinding> factsOf(Path root, String command) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final String file = REGISTRY_DIRECTORY + "/" + command + ".toml";
        if (!Files.isRegularFile(root.resolve(SCHEMA_DIRECTORY)
                .resolve(command + "-arguments.json"))) {
            findings.add(missing(file, Fact.ARGUMENT_SCHEMA, command));
        }
        if (!Files.isRegularFile(root.resolve(SCHEMA_DIRECTORY)
                .resolve(command + "-result.json"))) {
            findings.add(missing(file, Fact.RESULT_SCHEMA, command));
        }
        if (!typedModelExists(root, command)) {
            findings.add(missing(file, Fact.TYPED_MODEL, command));
        }
        if (!declaresFailures(root, command)) {
            findings.add(missing(file, Fact.FAILURE_SET, command));
        }
        findings.addAll(boundVectorFindings(root, file, command));
        if (!scenarioNames(root, command)) {
            findings.add(missing(file, Fact.INTEROP_SCENARIO, command));
        }
        findings.addAll(classificationFindings(root, file, command));
        findings.addAll(schemaMirrorFindings(root, file, command));
        findings.addAll(catalogFindings(root, file, command));
        return Collections.unmodifiableList(findings);
    }

    /**
     * Whether some typed model names this command.
     *
     * <p>Found by what a file says rather than by what it is called. A check that guessed the file
     * name from the wire name would be inventing a convention and then enforcing its own guess:
     * every command would have to be named the way this check expected, and a perfectly good model
     * under another name would read as an absent one. A model for a command is a source file that
     * names that command's wire name, which is a thing the file states about itself.</p>
     *
     * @param root the repository root
     * @param command the command's wire name
     * @return whether any source under the command package names it
     */
    private static boolean typedModelExists(Path root, String command) {
        final Path directory = root.resolve(MODEL_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> sources = Files.walk(directory)) {
            return sources.filter(file -> String.valueOf(file.getFileName()).endsWith(".java"))
                    .map(CommandConformance::read)
                    .anyMatch(written -> written.contains("\"" + command + "\""));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(directory + " is not readable", unreadable);
        }
    }

    /** Where a command's typed model lives, which is the Sling-only bundle's command package. */
    public static final String MODEL_DIRECTORY = "core/src/main/java/rs/slingshot/agent/command";

    private static boolean declaresFailures(Path root, String command) {
        final Path row = root.resolve(REGISTRY_DIRECTORY).resolve(command + ".toml");
        return Files.isRegularFile(row) && read(row).contains("failure_categories");
    }

    private static List<PolicyFinding> boundVectorFindings(Path root, String file, String command) {
        final Path vectors = root.resolve(VECTORS_FILE);
        if (!Files.isRegularFile(vectors)) {
            return List.of(missing(file, Fact.BOUND_VECTORS, command));
        }
        final String written = read(vectors);
        final List<PolicyFinding> findings = new ArrayList<>();
        for (final String edge : List.of(AT_THE_BOUND, PAST_THE_BOUND)) {
            if (!hasVectorAt(written, command, edge)) {
                findings.add(new PolicyFinding(file, PolicyFinding.NO_LINE,
                        Fact.BOUND_VECTORS.rule(), command + " has no vector " + edge));
            }
        }
        return Collections.unmodifiableList(findings);
    }

    /**
     * Whether one vector sits at, or one past, a bound this command reads.
     *
     * <p>Found by the members a vector declares about itself — which command it is for and which
     * side of the bound it sits on — rather than by a spelling in its identifier. An identifier is
     * a label somebody chose; the edge is what the vector is.</p>
     *
     * @param written the committed vector file
     * @param command the command's wire name
     * @param edge which side of the bound
     * @return whether such a vector is committed
     */
    private static boolean hasVectorAt(String written, String command, String edge) {
        final List<String> kinds = members(written, "kind");
        final List<String> edges = members(written, "edge");
        final String belonging = command.replace('_', '-');
        return java.util.stream.IntStream.range(0, Math.min(kinds.size(), edges.size()))
                .anyMatch(vector -> kinds.get(vector).startsWith(belonging)
                        && edges.get(vector).equals(edge));
    }

    /**
     * One member of every vector, in the order the vectors are committed in.
     *
     * <p>Read member by member rather than by splitting the file into objects: a vector carries
     * whole documents inside it as escaped text, braces and all, so anything that tried to find an
     * object's boundaries would find them in the middle of one vector's own input.</p>
     *
     * @param written the committed vector file
     * @param member the member to read
     * @return that member of each vector, in order
     */
    private static List<String> members(String written, String member) {
        return java.util.regex.Pattern.compile("\\\"" + member + "\\\":\\\"([^\\\"]*)\\\"")
                .matcher(written)
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toUnmodifiableList());
    }

    /** How a vector sitting exactly at a bound declares which side it is on. */
    public static final String AT_THE_BOUND = "at";

    /** How a vector one past a bound declares the same. */
    public static final String PAST_THE_BOUND = "past";

    /**
     * Whether some interop scenario declares itself to be about this command.
     *
     * <p>Declared, not merely mentioning: a scenario whose kind is {@code command} and whose
     * feature is this command's own wire name. A substring search would count a scenario that named
     * the command in passing — in a sentence about something else — as proof that the command is
     * exercised on a running instance, which is exactly the claim this fact exists to make
     * honestly.</p>
     *
     * @param root the repository root
     * @param command the command's wire name
     * @return whether a scenario declares it
     */
    private static boolean scenarioNames(Path root, String command) {
        final Path directory = root.resolve(SCENARIO_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .map(CommandConformance::read)
                    .anyMatch(written -> written.contains("kind = \"command\"")
                            && written.contains("feature = \"" + command + "\""));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(directory + " is not readable", unreadable);
        }
    }

    /**
     * Whether one row's access class and key requirement are the client's own.
     *
     * @param root the repository root
     * @param file what to call the row in a finding
     * @param command the command's wire name
     * @return one finding per member that differs from the client's classification
     */
    private static List<PolicyFinding> classificationFindings(Path root, String file,
                                                              String command) {
        final Path carried = root.resolve(CLIENT_CLASSIFICATION);
        if (!Files.isRegularFile(carried)) {
            return List.of(new PolicyFinding(file, PolicyFinding.NO_LINE,
                    Fact.CLIENT_CLASSIFICATION_AGREES.rule(),
                    command + " cannot be compared: the client's classification is not carried"));
        }
        final String published = read(carried);
        final Path row = root.resolve(REGISTRY_DIRECTORY).resolve(command + ".toml");
        if (!Files.isRegularFile(row)) {
            return List.of();
        }
        final String declared = read(row);
        final List<PolicyFinding> findings = new ArrayList<>();
        for (final String member : List.of("access", "operation_key")) {
            final String theirs = memberOf(published, command, member);
            if (!theirs.isEmpty() && !declared.contains(member + " = \"" + theirs + "\"")) {
                findings.add(new PolicyFinding(file, PolicyFinding.NO_LINE,
                        Fact.CLIENT_CLASSIFICATION_AGREES.rule(),
                        command + " declares a " + member + " the client does not: the client says "
                                + theirs));
            }
        }
        return Collections.unmodifiableList(findings);
    }

    /**
     * Where the schemas one command is held to are committed, under the client's own file names.
     *
     * @param root the repository root
     * @param command the command's wire name
     * @param role which of the two schemas
     * @return where that schema is committed
     */
    public static Path schemaFile(Path root, String command, String role) {
        return root.resolve(SCHEMA_DIRECTORY).resolve(command + "-" + role + ".json");
    }

    /**
     * Whether both committed schemas are the client's own bytes.
     *
     * <p>Compared as digests over the whole file rather than member by member, because the client
     * publishes its schemas as their own canonical bytes and hashes the file: anything this side
     * added — a description, a different identifier, a member order — is a different document and
     * therefore a different contract, whatever it happens to mean.</p>
     *
     * @param root the repository root
     * @param file the row this finding is reported against
     * @param command the command's wire name
     * @return one finding per role that differs, and none where both are the client's
     */
    private static List<PolicyFinding> schemaMirrorFindings(Path root, String file,
                                                            String command) {
        final Path carried = root.resolve(CLIENT_SCHEMAS);
        if (!Files.isRegularFile(carried)) {
            return List.of(new PolicyFinding(file, PolicyFinding.NO_LINE,
                    Fact.CLIENT_SCHEMA_AGREES.rule(),
                    command + " cannot be compared: the client's schema manifest is not carried"));
        }
        final String published = read(carried);
        final List<PolicyFinding> findings = new ArrayList<>();
        for (final String role : SCHEMA_ROLES) {
            final String theirs = schemaDigestOf(published, command, role);
            if (theirs.isEmpty()) {
                continue;
            }
            final Path committed = schemaFile(root, command, role);
            if (!Files.isRegularFile(committed)) {
                // A schema that is not there is already reported by the fact that says so. Saying
                // it twice would make somebody adding a command read two findings to learn one
                // thing, which is the opposite of what reporting them all at once is for.
                continue;
            }
            final String ours = digestOf(committed);
            if (!ours.equals(theirs)) {
                findings.add(new PolicyFinding(file, PolicyFinding.NO_LINE,
                        Fact.CLIENT_SCHEMA_AGREES.rule(), command + "'s " + role
                                + " schema is not the client's: this side commits " + ours
                                + " and the client publishes " + theirs));
            }
        }
        return Collections.unmodifiableList(findings);
    }

    /**
     * Whether one row says what the client's catalog says about the same command.
     *
     * <p>Compared as sets rather than in order, because the order a row lists its categories in is
     * this side's own. A category the row declares and the client does not is one a caller will
     * receive and be unable to interpret; one the client declares and the row does not is a failure
     * the client is ready for and this side will report as something else.</p>
     *
     * <p>A row may declare a category it cannot yet produce, and say why in {@code unproduced}.
     * That is a gap somebody decided rather than a gap nobody noticed, and it stays visible in the
     * row itself.</p>
     *
     * @param root the repository root
     * @param file the row this finding is reported against
     * @param command the command's wire name
     * @return one finding per disagreement, and none where the two halves agree
     */
    private static List<PolicyFinding> catalogFindings(Path root, String file, String command) {
        final Path carried = root.resolve(CLIENT_CATALOG);
        final Path row = root.resolve(REGISTRY_DIRECTORY).resolve(command + ".toml");
        if (!Files.isRegularFile(carried) || !Files.isRegularFile(row)) {
            return List.of();
        }
        final String declared = read(row);
        final Set<String> theirs = catalogCategoriesOf(read(carried), command);
        if (theirs.isEmpty()) {
            return List.of();
        }
        final Set<String> ours = declaredCategoriesOf(declared);
        final List<PolicyFinding> findings = new ArrayList<>();
        theirs.stream()
                .filter(category -> !ours.contains(category))
                .filter(category -> !declared.contains(category))
                .forEach(category -> findings.add(new PolicyFinding(file, PolicyFinding.NO_LINE,
                        Fact.CLIENT_CATALOG_AGREES.rule(), command + " declares no " + category
                                + ", which the client publishes and will be ready for")));
        ours.stream()
                .filter(category -> !theirs.contains(category))
                .forEach(category -> findings.add(new PolicyFinding(file, PolicyFinding.NO_LINE,
                        Fact.CLIENT_CATALOG_AGREES.rule(), command + " declares " + category
                                + ", which the client does not publish and could not interpret")));
        return Collections.unmodifiableList(findings);
    }

    private static Set<String> catalogCategoriesOf(String published, String command) {
        final java.util.regex.Matcher entry = java.util.regex.Pattern
                .compile("\\{[^{]*?\\\"wire_name\\\":\\\"" + command + "\\\"")
                .matcher(published);
        if (!entry.find()) {
            return Set.of();
        }
        final int from = published.lastIndexOf("\"failure_categories\":[", entry.end());
        if (from < 0) {
            return Set.of();
        }
        final int end = published.indexOf(']', from);
        return new TreeSet<>(List.of(published.substring(
                        from + "\"failure_categories\":[".length(), end).split(",")).stream()
                .map(category -> category.replace("\"", "").strip())
                .filter(category -> !category.isEmpty())
                .toList());
    }

    private static Set<String> declaredCategoriesOf(String row) {
        final java.util.regex.Matcher declared = java.util.regex.Pattern
                .compile("failure_categories = \\[([^\\]]*)\\]")
                .matcher(row);
        if (!declared.find()) {
            return Set.of();
        }
        return new TreeSet<>(List.of(declared.group(1).split(",")).stream()
                .map(category -> category.replace("\"", "").strip())
                .filter(category -> !category.isEmpty())
                .toList());
    }

    private static String schemaDigestOf(String published, String command, String role) {
        return java.util.regex.Pattern
                .compile("\\\"" + command + "\\\":\\{[^}]*\\\"" + role
                        + "\\\":\\\"([0-9a-f]{64})\\\"")
                .matcher(published)
                .results()
                .map(match -> match.group(1))
                .findFirst()
                .orElse("");
    }

    private static String digestOf(Path file) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(file)));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        } catch (final java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is not available on this runtime", absent);
        }
    }

    private static String memberOf(String published, String command, String member) {
        return java.util.regex.Pattern
                .compile("\\\"" + command + "\\\":\\{[^}]*\\\"" + member + "\\\":\\\"([a-z_]+)\\\"")
                .matcher(published)
                .results()
                .map(match -> match.group(1))
                .findFirst()
                .orElse("");
    }

    private static List<PolicyFinding> divergences(Set<String> declared, Set<String> published) {
        final List<PolicyFinding> findings = new ArrayList<>();
        declared.stream()
                .filter(command -> !published.contains(command))
                .map(command -> new PolicyFinding(REGISTRY_DIRECTORY + "/" + command + ".toml",
                        PolicyFinding.NO_LINE, Divergence.NOT_IN_THE_CLIENT_TABLE.rule(), command))
                .forEach(findings::add);
        published.stream()
                .filter(command -> !declared.contains(command))
                .map(command -> new PolicyFinding(CLIENT_TABLE, PolicyFinding.NO_LINE,
                        Divergence.NOT_IN_THIS_REGISTRY.rule(), command))
                .forEach(findings::add);
        return Collections.unmodifiableList(findings);
    }

    private static PolicyFinding missing(String file, Fact fact, String command) {
        return new PolicyFinding(file, PolicyFinding.NO_LINE, fact.rule(), command);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }
}
