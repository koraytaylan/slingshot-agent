// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The three ways a log line could stop being findable, refused by parsing rather than by review.
 *
 * <p>An operator with a console row has to be able to find the logs, and an operator with a log
 * line has to be able to find the console row. That works only while every line goes through one
 * writer, because the writer is where the operation identifier is attached and where the redaction
 * corpus is applied. A line written past it is findable by nobody and redacted by nothing.</p>
 *
 * <p>Three findings rather than one, because they are three different mistakes made by three
 * different people. Reaching for a logger is somebody who did not know there was a writer;
 * formatting a message is somebody who knew and wanted an easier call site; interpolating something
 * the corpus covers is the one that actually leaks. A single finding covering all three would tell
 * each of them to go and read the same paragraph.</p>
 *
 * <p>Naming a refused form in a comment is not using it. Every rule here reads source with its
 * comments removed, so the paragraph explaining why a form is refused can name the form.</p>
 */
public final class LogStatementPolicy {

    /** Where the logging rules are declared, beside every other source rule. */
    public static final String POLICY_FILE = "policy/source-policy.toml";

    /** The rule a source reaching for a logger of its own is reported under. */
    public static final String DIRECT_LOGGER = "direct-logger-call";

    /** The rule a message somebody formatted is reported under. */
    public static final String FORMATTED_STATEMENT = "formatted-log-statement";

    /** The rule a statement carrying something the corpus covers is reported under. */
    public static final String CORPUS_INTERPOLATION = "corpus-interpolated-log";

    /** How a statement that hands the writer an event begins, which is what makes a line a line. */
    private static final String STATEMENT = "LogEvent";

    private final String writer;
    private final String writerPackage;
    private final List<String> loggers;
    private final List<String> formats;
    private final List<String> planted;

    private LogStatementPolicy(String writer, String writerPackage, List<String> loggers,
                               List<String> formats, List<String> planted) {
        this.writer = writer;
        this.writerPackage = writerPackage;
        this.loggers = loggers;
        this.formats = formats;
        this.planted = planted;
    }

    /** The result of reading the rules: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * Rules that satisfied their shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(LogStatementPolicy policy) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * Reads the rules this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason there is none
     */
    public static Outcome read(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(POLICY_FILE), SourcePolicy.shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new LogStatementPolicy(document.text("logging.writer"),
                document.text("logging.package"),
                document.rows(SourcePolicy.LOGGER_ROWS).stream()
                        .map(row -> row.text("symbol")).toList(),
                document.rows(SourcePolicy.LOG_FORM_ROWS).stream()
                        .map(row -> row.text("form")).toList(),
                plantedValues(root)));
    }

    /**
     * Every value the redaction corpus plants, which is what a log statement may never carry.
     *
     * <p>Read from the corpus rather than listed here, so a kind somebody adds is covered by this
     * rule the day it is added rather than the day somebody remembers this file.</p>
     *
     * @param root the repository root
     * @return the planted values, or none where the corpus does not read
     */
    private static List<String> plantedValues(Path root) {
        return RedactionAudit.read(root) instanceof final RedactionAudit.Loaded loaded
                ? loaded.audit().corpus().stream().map(RedactionAudit.Secret::planted).toList()
                : List.of();
    }

    /**
     * Every main source in this repository, held to all three rules.
     *
     * @param root the repository root
     * @return one finding per source that breaks one, naming which
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .filter(source -> !root.relativize(source).toString().replace('\\', '/')
                        .contains(writerPackage))
                .forEach(source -> findings.addAll(inFile(root.relativize(source).toString(),
                        source)));
        return PolicyReport.of(findings);
    }

    /**
     * One source, held to all three rules.
     *
     * @param named how the finding names the file
     * @param file where to read it from
     * @return one finding per rule it breaks
     */
    public List<PolicyFinding> inFile(String named, Path file) {
        final String source = withoutComments(RepositoryTree.text(file));
        final List<PolicyFinding> findings = new ArrayList<>();
        loggers.stream()
                .filter(source::contains)
                .map(symbol -> PolicyFinding.inFile(named, DIRECT_LOGGER, symbol))
                .forEach(findings::add);
        statementsIn(source).forEach(statement -> {
            formats.stream()
                    .filter(statement::contains)
                    .map(format -> PolicyFinding.inFile(named, FORMATTED_STATEMENT, format))
                    .forEach(findings::add);
            if (statement.contains("\" +") || statement.contains("+ \"")) {
                findings.add(PolicyFinding.inFile(named, FORMATTED_STATEMENT, statement.trim()));
            }
            planted.stream()
                    .filter(statement::contains)
                    .map(value -> PolicyFinding.inFile(named, CORPUS_INTERPOLATION, value))
                    .forEach(findings::add);
        });
        return findings;
    }

    /**
     * Every log statement one source holds, as the text between the event and the end of its line.
     *
     * <p>Read a statement at a time rather than a file at a time, so a formatted message in one
     * place and a corpus value in another are two findings rather than one file that has both.</p>
     *
     * @param source the source, with its comments already removed
     * @return the statements, in the order they appear
     */
    private List<String> statementsIn(String source) {
        return source.lines()
                .filter(line -> line.contains(STATEMENT) || line.contains(writer + "."))
                .toList();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .lines()
                .map(line -> line.indexOf("//") >= 0 ? line.substring(0, line.indexOf("//")) : line)
                .reduce("", (all, line) -> all + line + "\n");
    }

    private static boolean isTestSource(Path relative) {
        for (final Path segment : relative) {
            if ("test".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
