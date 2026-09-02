// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The pattern every significant type declares, verified against its own structural signature.
 *
 * <p>A pattern that has to be declared with a reason is a pattern somebody chose; one that is
 * merely present is a pattern somebody copied. So the register is data, the vocabulary of patterns
 * is closed, and each pattern's signature is something the build can refuse — a declared builder
 * whose built type has a setter is not a builder, whatever the row says.</p>
 *
 * <p>The correspondence runs both ways. A significant type with no row cannot appear without
 * somebody deciding what it is, and a row naming a type that no longer exists cannot rot quietly in
 * the register.</p>
 */
public final class DesignPatternRegister {

    private static final String REGISTER_FILE = "policy/design-patterns.toml";

    private static final String PATTERN_ROWS = "pattern";

    private static final String TYPE_ROWS = "type";

    /** How a builder's closing method is named. */
    private static final String BUILD_METHOD = "build";

    /** How a document reader's loading methods are named. */
    private static final List<String> READ_METHODS = List.of("read", "load", "parse");

    /** How a setter is spelled, which a value and a built type both refuse to have. */
    private static final String SETTER_PREFIX = "set";

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final List<PatternRow> patterns;
    private final List<TypeRow> types;

    private DesignPatternRegister(List<PatternRow> patterns, List<TypeRow> types) {
        this.patterns = patterns;
        this.types = types;
    }

    /**
     * One pattern the vocabulary holds.
     *
     * @param name the pattern's own name
     * @param signature the structure the checker verifies
     * @param reason why the pattern is worth having at all
     */
    public record PatternRow(String name, String signature, String reason) {
    }

    /**
     * One type, and the pattern it declares.
     *
     * @param name the type's own name
     * @param pattern the pattern it implements
     * @param reason why that pattern, in the words of somebody who chose it
     */
    public record TypeRow(String name, String pattern, String reason) {
    }

    /** The result of reading the register: the register, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A register that satisfied its shape completely.
     *
     * @param register the loaded register
     */
    public record Loaded(DesignPatternRegister register) implements Outcome {
    }

    /**
     * A read that produced no register.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the register is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("design-patterns")
                .rows(PATTERN_ROWS, row -> row.text("name").text("signature").text("reason"))
                .rows(TYPE_ROWS, row -> row.text("name").text("pattern").text("reason"))
                .build();
    }

    /**
     * Reads the register this repository commits.
     *
     * @param root the repository root
     * @return the register, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readRegister(root.resolve(REGISTER_FILE));
    }

    /**
     * Reads a register from wherever it sits.
     *
     * @param register the register document
     * @return the register, or the one reason the document was refused
     */
    public static Outcome readRegister(Path register) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(register, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final List<PatternRow> patterns = document.rows(PATTERN_ROWS).stream()
                .map(row -> new PatternRow(row.text("name"), row.text("signature"), row.text("reason")))
                .toList();
        final List<TypeRow> types = document.rows(TYPE_ROWS).stream()
                .map(row -> new TypeRow(row.text("name"), row.text("pattern"), row.text("reason")))
                .toList();
        final Optional<TypeRow> unknown = types.stream()
                .filter(row -> patterns.stream().noneMatch(pattern -> pattern.name().equals(row.pattern())))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(unknown.get().name() + " declares the pattern "
                    + unknown.get().pattern() + ", which the vocabulary does not hold");
        }
        return new Loaded(new DesignPatternRegister(patterns, types));
    }

    /**
     * Every pattern the vocabulary holds.
     *
     * @return the pattern rows, in the register's own order
     */
    public List<PatternRow> patterns() {
        return Collections.unmodifiableList(patterns);
    }

    /**
     * Every type the register names.
     *
     * @return the type rows, in the register's own order
     */
    public List<TypeRow> types() {
        return Collections.unmodifiableList(types);
    }

    /**
     * Holds every significant type to the register and every register row to the source.
     *
     * @param root the repository root
     * @return one finding per significant type with no row, per row naming a type that does not
     *     exist, and per declared pattern whose structure does not match its signature
     */
    public PolicyReport against(Path root) {
        final SequencedMap<String, TypeDeclaration<?>> significant = significantTypes(root);
        final List<PolicyFinding> findings = new ArrayList<>();
        significant.keySet().stream()
                .filter(name -> types.stream().noneMatch(row -> row.name().equals(name)))
                .map(name -> PolicyFinding.inFile(REGISTER_FILE, "pattern-register",
                        name + " is a significant type and the register names no pattern for it"))
                .forEach(findings::add);
        types.stream()
                .filter(row -> !significant.containsKey(row.name()))
                .map(row -> PolicyFinding.inFile(REGISTER_FILE, "pattern-register",
                        row.name() + " has a register row and no such significant type exists"))
                .forEach(findings::add);
        types.stream()
                .filter(row -> significant.containsKey(row.name()))
                .forEach(row -> signatureFindings(row, significant.get(row.name()), significant)
                        .forEach(findings::add));
        return PolicyReport.of(findings);
    }

    /**
     * Every public top-level type this repository declares in a main source.
     *
     * @param root the repository root
     * @return the types, by name, in the order the sources are read
     */
    public SequencedMap<String, TypeDeclaration<?>> significantTypes(Path root) {
        final SequencedMap<String, TypeDeclaration<?>> significant = new LinkedHashMap<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .filter(source -> !"package-info.java".equals(String.valueOf(source.getFileName())))
                .forEach(source -> parse(source).getTypes().stream()
                        .filter(type -> type.hasModifier(Modifier.Keyword.PUBLIC))
                        .forEach(type -> significant.put(type.getNameAsString(), type)));
        return significant;
    }

    private List<PolicyFinding> signatureFindings(TypeRow row, TypeDeclaration<?> type,
                                                  SequencedMap<String, TypeDeclaration<?>> known) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final String file = REGISTER_FILE;
        switch (row.pattern()) {
            case "value-object" -> {
                if (!(type instanceof RecordDeclaration) && !hasValueEquality(type)) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares value-object and has no value equality"));
                }
            }
            case "builder" -> findings.addAll(builderFindings(row, type, known));
            case "closed-outcome", "closed-value" -> {
                if (!type.hasModifier(Modifier.Keyword.SEALED)) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares " + row.pattern() + " and is not sealed"));
                }
            }
            case "stateless-policy" -> {
                if (!instanceFields(type).isEmpty()) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares stateless-policy and holds instance state"));
                }
            }
            case "document-reader" -> {
                if (readMethods(type).isEmpty()) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares document-reader and declares no static read"));
                }
                if (!declaresClosedOutcome(type)) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares document-reader and answers no closed outcome"));
                }
            }
            case "enumeration" -> {
                if (!(type instanceof EnumDeclaration)) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares enumeration and is not one"));
                }
            }
            case "strategy" -> findings.addAll(strategyFindings(row, type, known));
            case "template-method" -> findings.addAll(templateMethodFindings(row, type));
            case "protection-proxy" -> findings.addAll(protectionProxyFindings(row, type));
            case "accessor" -> {
                if (!setters(type).isEmpty()) {
                    findings.add(PolicyFinding.inFile(file, "pattern-signature",
                            row.name() + " declares accessor and declares " + setters(type)));
                }
            }
            default -> findings.add(PolicyFinding.inFile(file, "pattern-signature",
                    row.name() + " declares a pattern with no signature: " + row.pattern()));
        }
        return findings;
    }

    /**
     * Whether a declared template method is a sealed base with something left to fill in.
     *
     * <p>Both halves matter. Sealed, because the set of things that may extend it is a decision
     * somebody records rather than whatever happens to compile; abstract with an abstract method,
     * because a base with nothing left to fill in is a class pretending to be a pattern.</p>
     *
     * @param row the register row
     * @param type the type it names
     * @return one finding per half that does not hold
     */
    private List<PolicyFinding> templateMethodFindings(TypeRow row, TypeDeclaration<?> type) {
        final List<PolicyFinding> findings = new ArrayList<>();
        if (!type.hasModifier(Modifier.Keyword.SEALED)) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares template-method and is not sealed"));
        }
        if (!type.hasModifier(Modifier.Keyword.ABSTRACT)
                || type.getMethods().stream().noneMatch(method -> method.isAbstract())) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares template-method and leaves nothing to fill in"));
        }
        return findings;
    }

    /**
     * Whether a declared protection proxy holds one guarded thing behind one way in.
     *
     * <p>All three halves are the same claim said structurally. Final, because a subclass could
     * replace the guarding with nothing; one instance field, because a second one is a second thing
     * to reach and the register would no longer say which is guarded; one public instance method,
     * because a guard with two ways in has one way in and one way around.</p>
     *
     * @param row the register row
     * @param type the type it names
     * @return one finding per half that does not hold
     */
    private List<PolicyFinding> protectionProxyFindings(TypeRow row, TypeDeclaration<?> type) {
        final List<PolicyFinding> findings = new ArrayList<>();
        if (!type.hasModifier(Modifier.Keyword.FINAL)) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares protection-proxy and is not final"));
        }
        final List<String> held = instanceFields(type);
        if (held.size() != 1) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares protection-proxy and holds " + held.size()
                            + " things rather than the one it guards: " + held));
        }
        final List<String> waysIn = publicInstanceMethods(type);
        if (waysIn.size() != 1) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares protection-proxy and offers " + waysIn.size()
                            + " ways in rather than one: " + waysIn));
        }
        return findings;
    }

    /**
     * Whether a declared strategy is an interface every implementation of which the register names.
     *
     * <p>The second half is the one worth having. An interface whose implementations are all
     * declared is one where adding a way of doing the thing is a decision somebody records; one
     * where they are not is a list that describes whatever happened to exist when it was written.
     */
    private List<PolicyFinding> strategyFindings(TypeRow row, TypeDeclaration<?> type,
                                                 SequencedMap<String, TypeDeclaration<?>> known) {
        final List<PolicyFinding> findings = new ArrayList<>();
        if (!(type instanceof final ClassOrInterfaceDeclaration declared) || !declared.isInterface()) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares strategy and is not an interface"));
            return findings;
        }
        known.forEach((name, candidate) -> {
            if (!implementsInterface(candidate, row.name())) {
                return;
            }
            if (types.stream().noneMatch(registered -> registered.name().equals(name))) {
                findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                        name + " implements " + row.name() + " and the register does not name it"));
            }
        });
        return findings;
    }

    private static boolean implementsInterface(TypeDeclaration<?> candidate, String declared) {
        return candidate instanceof final ClassOrInterfaceDeclaration type
                && type.getImplementedTypes().stream()
                        .anyMatch(implemented -> declared.equals(implemented.getNameAsString()));
    }

    private static List<PolicyFinding> builderFindings(TypeRow row, TypeDeclaration<?> type,
                                                       SequencedMap<String, TypeDeclaration<?>> known) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final Optional<MethodDeclaration> build = type.getMethods().stream()
                .filter(method -> BUILD_METHOD.equals(method.getNameAsString()))
                .findFirst();
        if (build.isEmpty()) {
            findings.add(PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                    row.name() + " declares builder and has no build method"));
            return findings;
        }
        final String built = build.get().getType().asString();
        known.entrySet().stream()
                .filter(entry -> entry.getKey().equals(built))
                .filter(entry -> !setters(entry.getValue()).isEmpty())
                .map(entry -> PolicyFinding.inFile(REGISTER_FILE, "pattern-signature",
                        row.name() + " builds " + built + ", which declares " + setters(entry.getValue())))
                .forEach(findings::add);
        return findings;
    }

    private static List<String> setters(TypeDeclaration<?> type) {
        return type.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .filter(DesignPatternRegister::isSetter)
                .toList();
    }

    /**
     * Whether a method name is a setter rather than a name that merely begins with the same three
     * letters. A settled state is not a setter, and a rule that could not tell them apart would
     * refuse a name for its spelling rather than for what it does.
     */
    private static boolean isSetter(String name) {
        return name.startsWith(SETTER_PREFIX) && name.length() > SETTER_PREFIX.length()
                && Character.isUpperCase(name.charAt(SETTER_PREFIX.length()));
    }

    private static List<String> publicInstanceMethods(TypeDeclaration<?> type) {
        return type.getMethods().stream()
                .filter(method -> !method.isStatic())
                .filter(method -> method.hasModifier(Modifier.Keyword.PUBLIC))
                .map(MethodDeclaration::getNameAsString)
                .toList();
    }

    private static List<String> instanceFields(TypeDeclaration<?> type) {
        return type.getFields().stream()
                .filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream())
                .map(variable -> variable.getNameAsString())
                .toList();
    }

    private static List<MethodDeclaration> readMethods(TypeDeclaration<?> type) {
        return type.getMethods().stream()
                .filter(MethodDeclaration::isStatic)
                .filter(method -> READ_METHODS.stream()
                        .anyMatch(read -> method.getNameAsString().startsWith(read)))
                .toList();
    }

    private static boolean declaresClosedOutcome(TypeDeclaration<?> type) {
        return type.getMembers().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .anyMatch(nested -> nested.hasModifier(Modifier.Keyword.SEALED));
    }

    private static boolean hasValueEquality(TypeDeclaration<?> type) {
        final List<String> declared = type.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .toList();
        return declared.contains("equals") && declared.contains("hashCode");
    }

    private static boolean isTestSource(Path relative) {
        for (final Path segment : relative) {
            if ("test".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static CompilationUnit parse(Path source) {
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(RepositoryTree.text(source));
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + parsed.getProblems());
        }
        return parsed.getResult().orElseThrow();
    }
}
