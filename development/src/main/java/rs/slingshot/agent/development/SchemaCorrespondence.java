// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The committed schemas, the typed models they describe, and whether the two still agree.
 *
 * <p>The schemas exist so a second implementation has something to read. Nothing loads them at run
 * time and nothing validates against them, because the typed model is the validator and a second
 * validator with different bounds is precisely the failure the protocol plan was built to avoid.
 * What keeps them honest is this check rather than a habit: members compared in both directions,
 * every stated length compared with the bound the model actually reads, and every schema's digest
 * compared with its own bytes.</p>
 */
public final class SchemaCorrespondence {

    /** The rule every finding here is reported under. */
    public static final String RULE = "schema-correspondence";

    private static final String POLICY_FILE = "schemas/agent-protocol-digests.toml";

    private static final String SCHEMA_ROWS = "schema";

    private static final String BOUND_ROWS = "bound";

    /** How a typed model spells the list of members its document has. */
    private static final Pattern MEMBERS =
            Pattern.compile("List<String> MEMBERS\\s*=\\s*([^;]+);", Pattern.DOTALL);

    /** How a model declares one member's own name. */
    private static final Pattern CONSTANT =
            Pattern.compile("String (?<constant>[A-Z_]+)\\s*=\\s*\"(?<spelling>[^\"]+)\"");

    private final List<SchemaRow> schemas;
    private final List<BoundRow> bounds;

    private SchemaCorrespondence(List<SchemaRow> schemas, List<BoundRow> bounds) {
        this.schemas = schemas;
        this.bounds = bounds;
    }

    /**
     * One committed schema and the model it describes.
     *
     * @param path the schema's repository-relative path
     * @param model the model's repository-relative source path
     * @param digest the digest committed for the schema's bytes
     * @param reason what the document is for
     * @param unpublished why the client publishes no document of its own for this one,
     *     and empty where it does
     * @param members which list in the model names this document's members, for the models that
     *     serve several commands at once, and empty where the model's own {@code MEMBERS} is it
     */
    public record SchemaRow(String path, String model, String digest, String reason,
                            String unpublished, String members) {

        /** What an unstated {@code unpublished} means: the client publishes this document too. */
        public static final String PUBLISHED = "";

        /**
         * What an unstated {@code members} means: the model declares one list and this is it.
         *
         * <p>Named per row rather than per model because several commands share one reader where
         * their arguments are the same shape, and each of those commands still has its own
         * document. A model serving four commands cannot have four constants all called
         * {@code MEMBERS}, and splitting it into four files to satisfy a checker would be the
         * checker deciding the design.</p>
         */
        public static final String OWN_MEMBERS = "";

        /**
         * Whether the client publishes a document of its own for this schema.
         *
         * @return whether it does, which decides if the two halves can be compared at all
         */
        public boolean isPublishedByTheClient() {
            return PUBLISHED.equals(unpublished);
        }
    }

    /**
     * One length a schema states and a model reads from the contract.
     *
     * @param schema the schema stating it
     * @param member the member it is about
     * @param limit the contract limit the model reads
     */
    public record BoundRow(String schema, String member, String limit) {
    }

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param schemas the directory the committed schemas sit in
     * @param models the root the model paths are resolved against
     * @param contract the agent contract, which is where every bound a model reads lives
     * @param clientSchemas the client's own committed schemas, carried in unchanged
     * @param runtimeSources the source roots that become the bundle, which read no schema
     */
    public record Sources(Path schemas, Path models, Path contract, Path clientSchemas,
                          List<Path> runtimeSources) {

        /** Holds sources whose source roots nothing can change afterwards. */
        public Sources {
            runtimeSources = List.copyOf(runtimeSources);
        }

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve("schemas/agent-protocol"), root,
                    root.resolve("support/agent-contract.toml"),
                    root.resolve("development/src/test/resources/fixtures/schema-correspondence"
                            + "/client"),
                    List.of(root.resolve("core/src/main/java"), root.resolve("aem/src/main/java")));
        }

        /**
         * The same sources with the schemas read from somewhere else.
         *
         * @param elsewhere where the schemas sit instead
         * @return the sources
         */
        public Sources withSchemas(Path elsewhere) {
            return new Sources(elsewhere, models, contract, clientSchemas, runtimeSources);
        }

        /**
         * The same sources with the models read from somewhere else.
         *
         * @param elsewhere the root the model paths are resolved against instead
         * @return the sources
         */
        public Sources withModels(Path elsewhere) {
            return new Sources(schemas, elsewhere, contract, clientSchemas, runtimeSources);
        }
    }

    /** The result of reading the record: the correspondence, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A record that satisfied its shape completely.
     *
     * @param correspondence the loaded record
     */
    public record Loaded(SchemaCorrespondence correspondence) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the record is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("agent-protocol-digests")
                .rows(SCHEMA_ROWS, row -> row.text("path").text("model").text("digest")
                        .text("reason").optionalText("unpublished").optionalText("members"))
                .rows(BOUND_ROWS, row -> row.text("schema").text("member").text("limit"))
                .build();
    }

    /**
     * Reads the record this repository commits.
     *
     * @param root the repository root
     * @return the record, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readRecord(root.resolve(POLICY_FILE));
    }

    /**
     * Reads a record from wherever it sits.
     *
     * @param record the record document
     * @return the record, or the one reason the document was refused
     */
    public static Outcome readRecord(Path record) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(record, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new SchemaCorrespondence(
                document.rows(SCHEMA_ROWS).stream()
                        .map(row -> new SchemaRow(row.text("path"), row.text("model"),
                                row.text("digest"), row.text("reason"),
                                row.optionalText("unpublished").orElse(SchemaRow.PUBLISHED),
                                row.optionalText("members").orElse(SchemaRow.OWN_MEMBERS)))
                        .toList(),
                document.rows(BOUND_ROWS).stream()
                        .map(row -> new BoundRow(row.text("schema"), row.text("member"),
                                row.text("limit")))
                        .toList()));
    }

    /**
     * Every schema this record names.
     *
     * @return the rows, in the record's own order
     */
    public List<SchemaRow> schemas() {
        return java.util.Collections.unmodifiableList(schemas);
    }

    /**
     * Everything the schemas, the models, and this record disagree about.
     *
     * @param sources everywhere this check reads from
     * @return one finding per disagreement, each naming what was refused
     */
    public PolicyReport against(Sources sources) {
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(inventory(sources));
        schemas.forEach(row -> findings.addAll(correspondence(sources, row)));
        findings.addAll(runtimeLoading(sources));
        findings.addAll(client(sources));
        return PolicyReport.of(findings);
    }

    private List<PolicyFinding> inventory(Sources sources) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<String> committed = jsonUnder(sources.schemas()).stream()
                .map(file -> relative(sources, file))
                .toList();
        committed.stream()
                .filter(path -> schemas.stream().noneMatch(row -> row.path().endsWith(path)))
                .forEach(path -> findings.add(PolicyFinding.inFile(POLICY_FILE,
                        "schema-without-a-digest-row", path + " is committed and no row names it")));
        schemas.stream()
                .filter(row -> !Files.isRegularFile(sources.models().resolve(row.path())))
                .forEach(row -> findings.add(PolicyFinding.inFile(POLICY_FILE,
                        "digest-row-without-a-schema", row.path() + " is named and does not exist")));
        schemas.stream()
                .filter(row -> Files.isRegularFile(sources.models().resolve(row.path())))
                .filter(row -> !digestOf(sources.models().resolve(row.path())).equals(row.digest()))
                .forEach(row -> findings.add(PolicyFinding.inFile(row.path(),
                        "digest-does-not-match", "the committed bytes digest to "
                                + digestOf(sources.models().resolve(row.path()))
                                + " and the record says " + row.digest())));
        return findings;
    }

    private List<PolicyFinding> correspondence(Sources sources, SchemaRow row) {
        final Path schema = sources.models().resolve(row.path());
        final Path model = sources.models().resolve(row.model());
        if (!Files.isRegularFile(schema) || !Files.isRegularFile(model)) {
            return List.of();
        }
        final Set<String> declared = declaredMembers(contentOf(schema));
        final Set<String> modelled = modelledMembers(model, row.members());
        final List<PolicyFinding> findings = new ArrayList<>();
        declared.stream()
                .filter(member -> !modelled.contains(member))
                .forEach(member -> findings.add(PolicyFinding.inFile(row.path(),
                        "member-only-in-the-schema",
                        member + " is in the schema and not in " + simpleName(row.model()))));
        modelled.stream()
                .filter(member -> !declared.contains(member))
                .forEach(member -> findings.add(PolicyFinding.inFile(row.model(),
                        "member-only-in-the-model",
                        member + " is in " + simpleName(row.model()) + " and not in the schema")));
        findings.addAll(boundFindings(sources, row, contentOf(schema)));
        return findings;
    }

    private List<PolicyFinding> boundFindings(Sources sources, SchemaRow row, String schema) {
        final Map<String, Long> limits = contractLimits(sources.contract());
        return bounds.stream()
                .filter(bound -> row.path().endsWith(bound.schema()))
                .filter(bound -> stated(schema, bound.member()).isPresent())
                .filter(bound -> !stated(schema, bound.member()).orElseThrow()
                        .equals(limits.get(bound.limit())))
                .map(bound -> PolicyFinding.inFile(row.path(), "bound-disagrees",
                        bound.member() + " states " + stated(schema, bound.member()).orElseThrow()
                                + " and " + bound.limit() + " is " + limits.get(bound.limit())))
                .toList();
    }

    private static List<PolicyFinding> runtimeLoading(Sources sources) {
        return sources.runtimeSources().stream()
                .flatMap(root -> filesUnder(root, ".java").stream())
                .filter(file -> contentOf(file).contains("schemas/"))
                .map(file -> PolicyFinding.inFile(String.valueOf(file.getFileName()),
                        "schema-loaded-at-run-time",
                        "this class names the schema directory, and nothing in the bundle reads one"))
                .toList();
    }

    private List<PolicyFinding> client(Sources sources) {
        if (!Files.isDirectory(sources.clientSchemas())) {
            return List.of();
        }
        return schemas.stream()
                .filter(row -> Files.isRegularFile(sources.models().resolve(row.path())))
                .flatMap(row -> comparedWithClient(sources, row).stream())
                .toList();
    }

    /**
     * Where a command's own schemas are committed, which the client publishes digests for.
     *
     * <p>A schema under here is the client's own document mirrored, and what vouches for it is the
     * client's published manifest of digests rather than a second copy carried beside it: comparing
     * a mirrored document with itself would prove only that copying works.</p>
     */
    private static final String COMMAND_SCHEMAS = "command/";

    /**
     * How one committed schema is held against the client's, or the reason nothing holds it.
     *
     * <p>A schema with no client copy carried used to be skipped in silence, and that silence is
     * how fourteen commands came to be written against member names the other half does not send.
     * Skipping is now a finding of its own: a schema nothing on the other side is compared with is
     * a schema this side is free to invent, which is the one thing a protocol document must not
     * be.</p>
     *
     * @param sources everywhere this check reads from
     * @param row the committed schema
     * @return the disagreement, or nothing where the two halves agree
     */
    private static Optional<PolicyFinding> comparedWithClient(Sources sources, SchemaRow row) {
        if (row.path().contains(COMMAND_SCHEMAS)) {
            return Optional.empty();
        }
        if (!row.isPublishedByTheClient()) {
            // A document the other half does not publish cannot be compared with anything, and
            // saying so in the row is the difference between a gap somebody decided and a gap
            // nobody noticed. The reason travels with it.
            return Optional.empty();
        }
        if (!Files.isRegularFile(clientCopy(sources, row))) {
            return Optional.of(PolicyFinding.inFile(row.path(), "client-schema-uncompared",
                    "no copy of the client's own schema is carried for this document, so nothing"
                            + " compares the two halves of it"));
        }
        return disagreementWithClient(sources, row);
    }

    private static Optional<PolicyFinding> disagreementWithClient(Sources sources, SchemaRow row) {
        final Set<String> ours = declaredMembers(contentOf(sources.models().resolve(row.path())));
        final Set<String> theirs = declaredMembers(contentOf(clientCopy(sources, row)));
        if (ours.equals(theirs)) {
            return Optional.empty();
        }
        return Optional.of(PolicyFinding.inFile(row.path(), "client-schema-disagrees",
                "this side declares " + ours + " and the client's own schema declares " + theirs));
    }

    private static Path clientCopy(Sources sources, SchemaRow row) {
        return sources.clientSchemas().resolve(Path.of(row.path()).getFileName());
    }

    /**
     * The members one schema declares, from its properties.
     *
     * @param schema the schema's own bytes, as text
     * @return the member names
     */
    public static Set<String> declaredMembers(String schema) {
        final Set<String> declared = new LinkedHashSet<>();
        final Matcher properties = Pattern.compile("\"properties\"\\s*:\\s*\\{")
                .matcher(schema);
        while (properties.find()) {
            declared.addAll(namesIn(schema, properties.end()));
        }
        declared.removeAll(COUNTING_KEYWORDS);
        return declared;
    }

    /**
     * The schema keywords that count members rather than naming one.
     *
     * <p>A command may have a member called {@code properties} — eight of them do — and the value
     * of that member is a schema, not a list of members. Scanning it the way a {@code properties}
     * block is scanned turns the keywords describing it into members nobody declared. Only these
     * three are excluded, and deliberately not {@code type}: that one really is a member name in
     * this protocol, on every typed value there is.</p>
     */
    private static final Set<String> COUNTING_KEYWORDS =
            Set.of("additionalProperties", "maxProperties", "minProperties");

    private static Set<String> namesIn(String schema, int from) {
        final Set<String> names = new LinkedHashSet<>();
        int depth = 1;
        int index = from;
        while (index < schema.length() && depth > 0) {
            final char scalar = schema.charAt(index);
            if (scalar == '{') {
                depth = depth + 1;
            }
            if (scalar == '}') {
                depth = depth - 1;
            }
            if (scalar == '"' && depth == 1) {
                final int end = schema.indexOf('"', index + 1);
                // A member is a key, and a key is a quoted string with a colon after it. Taking
                // every quoted string took the values too, so a schema saying its type is an
                // object declared a member called "object".
                if (colonFollows(schema, end)) {
                    names.add(schema.substring(index + 1, end));
                }
                index = end;
            }
            index = index + 1;
        }
        return names;
    }

    private static boolean colonFollows(String schema, int quote) {
        int at = quote + 1;
        while (at < schema.length() && Character.isWhitespace(schema.charAt(at))) {
            at = at + 1;
        }
        return at < schema.length() && schema.charAt(at) == ':';
    }

    private static Set<String> modelledMembers(Path model, String named) {
        final String source = contentOf(model);
        final Matcher members = SchemaRow.OWN_MEMBERS.equals(named)
                ? MEMBERS.matcher(source)
                : Pattern.compile("List<String> " + Pattern.quote(named) + "\\s*=\\s*([^;]+);",
                        Pattern.DOTALL).matcher(source);
        if (!members.find()) {
            return Set.of();
        }
        final String initializer = members.group(1).strip();
        if (initializer.endsWith(".MEMBERS")) {
            // Looked for across the module rather than beside the model, for the same reason a
            // borrowed member is: a shared list belongs to the type that owns it, and that type
            // lives with the vocabulary rather than with each command that borrows from it.
            final String owner = initializer.substring(0, initializer.indexOf(".MEMBERS")).strip();
            return declaring(model, owner + ".java")
                    .map(borrowed -> modelledMembers(borrowed, SchemaRow.OWN_MEMBERS))
                    .orElse(Set.of());
        }
        return namesOf(model, source, initializer);
    }

    /**
     * The spelling one constant reference resolves to, following it into the file that declares it.
     *
     * <p>A model may name a member the protocol declares elsewhere — a paged command's window is
     * the window type's member, not a second one spelled the same. Reading the reference as its own
     * text would report the Java expression as a document member, which is a finding about this
     * checker rather than about the model. So a qualified reference is followed, the same way a
     * borrowed member list already is.</p>
     *
     * @param model the file being read, whose directory the owner is looked for in
     * @param reference the reference as written, such as {@code ResultWindow.ARGUMENT_MEMBER}
     * @return the spelling it resolves to, or the reference itself where nothing declares it
     */
    private static String followed(Path model, String reference) {
        final int dot = reference.lastIndexOf('.');
        if (dot < 1) {
            return reference;
        }
        final java.util.Optional<Path> owner =
                declaring(model, reference.substring(0, dot).strip() + ".java");
        if (owner.isEmpty()) {
            return reference;
        }
        final Matcher declared = CONSTANT.matcher(contentOf(owner.get()));
        final String wanted = reference.substring(dot + 1).strip();
        while (declared.find()) {
            if (wanted.equals(declared.group("constant"))) {
                return declared.group("spelling");
            }
        }
        return reference;
    }

    /**
     * Where one type's own source is, looked for across the module rather than beside the model.
     *
     * <p>A borrowed member need not come from the same package. A paged command's window member
     * belongs to the window type, which lives with the framework rather than with the commands
     * that use it — so looking only at siblings would find nothing and report the reference as an
     * undeclared member, which is a finding about where a file sits rather than about the model.
     * </p>
     *
     * @param model the file being read, whose source root is walked
     * @param fileName the file the owning type is declared in
     * @return that file where the module holds one, and nothing where it does not
     */
    private static java.util.Optional<Path> declaring(Path model, String fileName) {
        Path root = model.getParent();
        while (root != null && !"java".equals(String.valueOf(root.getFileName()))) {
            root = root.getParent();
        }
        if (root == null) {
            return java.util.Optional.empty();
        }
        try (java.util.stream.Stream<Path> sources = Files.walk(root)) {
            return sources.filter(source -> fileName.equals(String.valueOf(source.getFileName())))
                    .findFirst();
        } catch (final java.io.IOException unreadable) {
            return java.util.Optional.empty();
        }
    }

    private static Set<String> namesOf(Path model, String source, String initializer) {
        final Map<String, String> constants = new LinkedHashMap<>();
        final Matcher declared = CONSTANT.matcher(source);
        while (declared.find()) {
            constants.put(declared.group("constant"), declared.group("spelling"));
        }
        final Set<String> names = new LinkedHashSet<>();
        final Optional<MethodCallExpr> call = listCall(initializer);
        call.ifPresent(expression -> expression.getArguments().forEach(argument ->
                names.addAll(namedBy(model, constants, argument.toString().replace("\"", "")))));
        return names;
    }

    /**
     * What one entry of a member list names, which may be a whole nested document's worth.
     *
     * <p>The client's schemas nest one document inside another — a result window inside every
     * paged argument, a predicate inside every search — and a schema declares the nested document's
     * members alongside the outer ones. So a model has to be able to say "and everything the window
     * declares" rather than restating four member names that belong to the window type. An entry
     * ending in {@code .MEMBERS} is that: the nested type's own list, expanded here.</p>
     *
     * <p>Restating them instead would be four copies of the window's members across nine commands,
     * which is exactly the drift this check exists to catch.</p>
     *
     * @param model the file being read
     * @param constants the model's own constants, by name
     * @param written the entry as it was written
     * @return the member names it contributes
     */
    private static Set<String> namedBy(Path model, Map<String, String> constants, String written) {
        if (constants.containsKey(written)) {
            return Set.of(constants.get(written));
        }
        final Set<String> named = new LinkedHashSet<>();
        named.add(followed(model, written));
        // A member borrowed from another type carries that type's own document with it. The
        // client's schemas declare a nested document's members beside the outer ones — a window's
        // mode and limit sit in the same schema as the root path that names the search — so a
        // command that carries a window carries the window's members too, and saying so once here
        // is what keeps nine commands from restating four names each.
        final int dot = written.lastIndexOf('.');
        if (dot > 0) {
            declaring(model, written.substring(0, dot).strip() + ".java")
                    .map(borrowed -> modelledMembers(borrowed, SchemaRow.OWN_MEMBERS))
                    .ifPresent(named::addAll);
        }
        return Collections.unmodifiableSet(named);
    }

    private static Optional<MethodCallExpr> listCall(String initializer) {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        final Expression expression = StaticJavaParser.parseExpression(initializer);
        return expression instanceof final MethodCallExpr call
                ? Optional.of(call)
                : Optional.empty();
    }

    private static Optional<Long> stated(String schema, String member) {
        final Matcher stated = Pattern.compile("\"" + member
                + "\"\\s*:\\s*\\{[^}]*\"maxLength\"\\s*:\\s*(\\d+)", Pattern.DOTALL)
                .matcher(schema);
        return stated.find() ? Optional.of(Long.parseLong(stated.group(1))) : Optional.empty();
    }

    private static Map<String, Long> contractLimits(Path contract) {
        final Map<String, Long> limits = new LinkedHashMap<>();
        if (!Files.isRegularFile(contract)) {
            return limits;
        }
        contentOf(contract).lines()
                .map(String::strip)
                .filter(line -> line.contains(" = ") && !line.startsWith("#"))
                .forEach(line -> reading(line).ifPresent(read -> limits.put(read.name(),
                        read.value())));
        return limits;
    }

    private record Reading(String name, long value) {
    }

    /** How many parts a declared bound is written in: its name and its value. */
    private static final int DECLARATION_PARTS = 2;

    private static Optional<Reading> reading(String line) {
        final String[] halves = line.split(" = ", DECLARATION_PARTS);
        try {
            return Optional.of(new Reading(halves[0].strip(), Long.parseLong(halves[1].strip())));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static String relative(Sources sources, Path file) {
        return sources.models().relativize(file).toString();
    }

    private static String simpleName(String model) {
        return String.valueOf(Path.of(model).getFileName()).replace(".java", "");
    }

    private static List<Path> jsonUnder(Path root) {
        return filesUnder(root, ".json");
    }

    private static List<Path> filesUnder(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String contentOf(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String digestOf(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is not available on this runtime", absent);
        }
    }
}
