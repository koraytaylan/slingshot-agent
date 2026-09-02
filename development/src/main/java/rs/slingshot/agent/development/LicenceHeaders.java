// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The licence expression every repository-owned file carries, decided over parsed files.
 *
 * <p>Parsing matters here more than it looks. A file that mentions the expression in a string
 * literal, in prose, or in a fixture it feeds to another checker has not carried a header, and a
 * check reading the first bytes would say it had. So a Java file is parsed and its comments are
 * read, an XML file is parsed and its comment nodes are read, and a hash-comment file is scanned by
 * a reader that knows a {@code #} inside a quoted string is not a comment.</p>
 */
public final class LicenceHeaders {

    private static final String POLICY_FILE = "policy/licence-headers.toml";

    private static final String KIND_ROWS = "kind";

    private static final String EXCLUSION_ROWS = "exclusion";

    private static final String SPDX_TAG = "SPDX-License-Identifier:";

    /**
     * The one parser every rule that decides against Java syntax reads through, at the language
     * level this repository compiles to.
     */
    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    /**
     * The parser feature that stops an external document-type definition being fetched. A rule set
     * naming a public definition is still parsed; nothing reaches out to read it.
     */
    private static final String EXTERNAL_DOCUMENT_TYPE_FEATURE =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private final String expression;
    private final String copyright;
    private final List<KindRow> kinds;
    private final List<ExclusionRow> exclusions;

    private LicenceHeaders(String expression, String copyright, List<KindRow> kinds,
                           List<ExclusionRow> exclusions) {
        this.expression = expression;
        this.copyright = copyright;
        this.kinds = kinds;
        this.exclusions = exclusions;
    }

    /**
     * One kind of repository-owned file, and the comment form its header is written in.
     *
     * @param name what the kind is called
     * @param suffix the file suffix that identifies it
     * @param comment the comment form: {@code line}, {@code xml}, or {@code hash}
     * @param prefix the characters that open a comment in that form
     */
    public record KindRow(String name, String suffix, String comment, String prefix) {
    }

    /**
     * A path the header rule does not apply to.
     *
     * @param path the repository-relative path or path segment
     * @param reason why the rule does not apply there
     */
    public record ExclusionRow(String path, String reason) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(LicenceHeaders policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the licence-header policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("licence-headers")
                .text("expression.spdx")
                .text("expression.copyright")
                .rows(KIND_ROWS, row -> row.text("name").text("suffix").text("comment").text("prefix"))
                .rows(EXCLUSION_ROWS, row -> row.text("path").text("reason"))
                .build();
    }

    /**
     * Reads the policy this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(POLICY_FILE), shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final List<ExclusionRow> exclusions = document.rows(EXCLUSION_ROWS).stream()
                .map(row -> new ExclusionRow(row.text("path"), row.text("reason")))
                .toList();
        final Optional<ExclusionRow> unexplained = exclusions.stream()
                .filter(row -> row.reason().isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused("the exclusion of " + unexplained.get().path() + " records no reason");
        }
        return new Loaded(new LicenceHeaders(
                document.text("expression.spdx"),
                document.text("expression.copyright"),
                document.rows(KIND_ROWS).stream()
                        .map(row -> new KindRow(row.text("name"), row.text("suffix"),
                                row.text("comment"), row.text("prefix")))
                        .toList(),
                exclusions));
    }

    /**
     * The expression every repository-owned file carries.
     *
     * @return the declared SPDX expression
     */
    public String expression() {
        return expression;
    }

    /**
     * The copyright line every repository-owned file carries beside the expression.
     *
     * @return the declared copyright line
     */
    public String copyright() {
        return copyright;
    }

    /**
     * Whether the rule applies to a path at all.
     *
     * @param relative the repository-relative path
     * @return {@code true} where the path is one this repository owns and the policy names its kind
     */
    public boolean applies(Path relative) {
        final String path = relative.toString();
        return exclusions.stream().noneMatch(exclusion -> path.contains(exclusion.path()))
                && kindOf(relative).isPresent();
    }

    /**
     * Holds every repository-owned file to the header rule.
     *
     * @param root the repository root
     * @return one finding per file carrying no header, the wrong expression, or the wrong copyright
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, "").stream()
                .filter(file -> applies(root.relativize(file)))
                .forEach(file -> findings.addAll(inFile(root.relativize(file).toString(), file)));
        return PolicyReport.of(findings);
    }

    /**
     * Holds one file to the header rule.
     *
     * @param name what to call the file in a finding
     * @param file the file to read
     * @return one finding per part of the header that is absent or different
     */
    public List<PolicyFinding> inFile(String name, Path file) {
        final KindRow kind = kindOf(Path.of(name)).orElseThrow(
                () -> new IllegalArgumentException("no declared kind covers " + name));
        final List<String> comments = comments(file, kind);
        final List<PolicyFinding> findings = new ArrayList<>();
        final Optional<String> spdx = comments.stream()
                .filter(comment -> comment.contains(SPDX_TAG))
                .findFirst();
        if (spdx.isEmpty()) {
            findings.add(PolicyFinding.inFile(name, "licence-header", "carries no " + SPDX_TAG));
        } else if (!spdx.get().contains(SPDX_TAG + " " + expression)) {
            findings.add(PolicyFinding.inFile(name, "licence-header",
                    "states " + spdx.get().strip() + " rather than " + expression));
        }
        if (comments.stream().noneMatch(comment -> comment.contains(copyright))) {
            findings.add(PolicyFinding.inFile(name, "licence-copyright",
                    "does not carry " + copyright));
        }
        return findings;
    }

    private Optional<KindRow> kindOf(Path relative) {
        return Optional.ofNullable(relative.getFileName())
                .map(Path::toString)
                .flatMap(name -> kinds.stream()
                        .filter(kind -> name.endsWith(kind.suffix()))
                        .findFirst());
    }

    private static List<String> comments(Path file, KindRow kind) {
        return switch (kind.comment()) {
            case "line" -> javaComments(file);
            case "xml" -> xmlComments(file);
            case "hash" -> hashComments(file);
            default -> throw new IllegalStateException("no reader knows the comment form "
                    + kind.comment());
        };
    }

    private static List<String> javaComments(Path file) {
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(RepositoryTree.text(file));
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(file + " does not parse: " + parsed.getProblems());
        }
        return parsed.getCommentsCollection()
                .map(collection -> collection.getComments().stream()
                        .map(Comment::getContent)
                        .toList())
                .orElseGet(List::of);
    }

    private static List<String> xmlComments(Path file) {
        final Document document = parseXml(file);
        final List<String> comments = new ArrayList<>();
        collectComments(document, comments);
        return List.copyOf(comments);
    }

    private static void collectComments(Node node, List<String> comments) {
        if (node.getNodeType() == Node.COMMENT_NODE) {
            comments.add(node.getNodeValue());
        }
        IntStream.range(0, node.getChildNodes().getLength())
                .mapToObj(index -> node.getChildNodes().item(index))
                .forEach(child -> collectComments(child, comments));
    }

    private static Document parseXml(Path file) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(EXTERNAL_DOCUMENT_TYPE_FEATURE, false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final byte[] bytes = RepositoryTree.text(file).getBytes(StandardCharsets.UTF_8);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (final ParserConfigurationException | SAXException | IOException failure) {
            throw new IllegalStateException(file + " is not a document this reader can parse", failure);
        }
    }

    /**
     * The comments in a file whose comments open with a hash, decided by a reader that knows a hash
     * inside a quoted string is a character and not a comment.
     */
    private static List<String> hashComments(Path file) {
        return RepositoryTree.text(file).lines()
                .map(LicenceHeaders::hashCommentIn)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * The comment one line carries, where a hash outside a quoted string opens one.
     *
     * <p>The quoting matters: a hash inside a value is a character, and a reader that treated it as
     * a comment would accept a document that names the header without carrying it.</p>
     */
    private static Optional<String> hashCommentIn(String line) {
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            final char character = line.charAt(index);
            if (quote != 0) {
                quote = character == quote ? 0 : quote;
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '#') {
                return Optional.of(line.substring(index + 1));
            }
        }
        return Optional.empty();
    }

}
