// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Whether this console can be read and operated by somebody who is not using a mouse or English.
 *
 * <p>Retrofitting either is far more expensive than doing it once, and a console with five pages is
 * exactly the size where it is still cheap. Granite renders server-side, so both are decidable from
 * the committed markup rather than from a browser — which is why this is a build check and not a
 * manual pass somebody does before a release and then does not do again.</p>
 *
 * <p>The literal-string rule is the one that keeps the rest true later. A dictionary is only
 * complete while nothing bypasses it, and the thing that bypasses it is always one string somebody
 * typed in a hurry — so a human-facing value that is not a declared key is refused by name, and the
 * correspondence is checked in both directions so the dictionary neither misses a key nor keeps one
 * nothing uses.</p>
 */
public final class ConsoleAccessibility {

    /** Where the console's own resources sit. */
    public static final String CONSOLE =
            "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content";

    /**
     * Where the entry an operator reaches the console through sits.
     *
     * <p>Read as part of the console because it is: it is the first thing a person sees, it carries
     * a title and a description in the same dictionary, and a literal there is a literal exactly
     * where somebody in another language starts.</p>
     */
    public static final String NAVIGATION = "ui.apps/src/main/content/jcr_root/apps/cq";

    /** Where the dictionary sits. */
    public static final String DICTIONARY =
            "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/i18n/en/.content.xml";

    /** Where the data sources that render values live, which is the other place a key is used. */
    public static final String SOURCES = "core/src/main/java/rs/slingshot/agent/console";

    /** The rule a human-facing value that is not a declared key is reported under. */
    public static final String LITERAL_STRING = "literal-string";

    /** The rule a control nothing names is reported under. */
    public static final String ACCESSIBLE_NAME = "accessible-name";

    /** The rule a table whose cells belong to no header is reported under. */
    public static final String HEADER_ASSOCIATION = "header-association";

    /** The rule an image with nothing to read instead is reported under. */
    public static final String TEXT_ALTERNATIVE = "text-alternative";

    /** The rule a control only a pointing device reveals is reported under. */
    public static final String HOVER_ONLY = "hover-only-control";

    /** The rule a key the console uses and the dictionary does not declare is reported under. */
    public static final String KEY_NOT_DECLARED = "key-used-and-not-declared";

    /** The rule a key the dictionary declares and nothing uses is reported under. */
    public static final String KEY_NOT_USED = "key-declared-and-not-used";

    /** How every key of this console's begins, which is what makes one recognisable as a key. */
    public static final String KEY_PREFIX = "slingshot.agent.";

    /** The attributes a person reads, every one of which must be a key rather than a sentence. */
    private static final List<String> HUMAN_FACING =
            List.of("jcr:title", "jcr:description", "text", "emptyText", "granite:label",
                    "fieldLabel", "title", "alt", "aria-label");

    /**
     * The node types nobody renders, whose titles are therefore not strings anybody reads.
     *
     * <p>A folder in this tree is a place other nodes sit, and its title is seen only by somebody
     * already looking at the repository — who is reading the tree rather than the console. Holding
     * it to the dictionary would put a key in the dictionary that nothing shows, which is the
     * finding on the other side of this same rule.</p>
     */
    private static final List<String> NOT_RENDERED = List.of("sling:Folder", "nt:folder",
            "sling:OrderedFolder");

    /** What names a control, any one of which is enough. */
    private static final List<String> NAMES = List.of("jcr:title", "granite:label", "aria-label",
            "fieldLabel", "text");

    /** The resource types a person operates, which therefore have to be named and reachable. */
    private static final List<String> CONTROLS = List.of("/table", "/button", "/anchorbutton",
            "/select", "/textfield", "/actionbutton", "/checkbox");

    /** The resource type a table's headers sit under. */
    private static final String COLUMNS = "columns";

    /** What a container that only a pointing device opens is called, wherever it is called it. */
    private static final List<String> HOVER_REVEALED = List.of("quickactions", "hover", "tooltip");

    /** The feature that keeps a document from reaching for one somewhere else. */
    private static final String EXTERNAL_DOCUMENT_TYPE_FEATURE =
            "http://apache.org/xml/features/disallow-doctype-decl";

    private ConsoleAccessibility() {
    }

    /**
     * Every console resource, held to all of it.
     *
     * @param root the repository root
     * @return one finding per rule any resource breaks, and one per key either side is missing
     */
    public static PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final Set<String> declared = declaredKeys(root.resolve(DICTIONARY));
        final Set<String> used = new LinkedHashSet<>();
        List.of(CONSOLE, NAVIGATION).forEach(tree ->
                RepositoryTree.filesUnder(root.resolve(tree), ".content.xml").forEach(file ->
                        findings.addAll(inFile(root.relativize(file).toString(), file, used))));
        usedInSources(root).forEach(used::add);
        used.stream()
                .filter(key -> !declared.contains(key))
                .map(key -> PolicyFinding.inFile(DICTIONARY, KEY_NOT_DECLARED, key))
                .forEach(findings::add);
        declared.stream()
                .filter(key -> !used.contains(key))
                .map(key -> PolicyFinding.inFile(DICTIONARY, KEY_NOT_USED, key))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * One console resource, held to every rule but the two about the dictionary as a whole.
     *
     * @param named how a finding names the file
     * @param file where to read it from
     * @param used where to record the keys this file uses
     * @return one finding per rule it breaks
     */
    public static List<PolicyFinding> inFile(String named, Path file, Set<String> used) {
        final List<PolicyFinding> findings = new ArrayList<>();
        elementsIn(parseXml(file)).stream()
                .filter(element -> !NOT_RENDERED.contains(element.getAttribute("jcr:primaryType")))
                .forEach(element -> {
                    findings.addAll(stringFindings(named, element, used));
                    findings.addAll(controlFindings(named, element));
                });
        return findings;
    }

    /**
     * Every human-facing value one element carries that is a sentence rather than a key.
     *
     * @param named how a finding names the file
     * @param element the element
     * @param used where to record the keys it does use
     * @return one finding per value that bypasses the dictionary
     */
    private static List<PolicyFinding> stringFindings(String named, Element element,
                                                      Set<String> used) {
        final List<PolicyFinding> findings = new ArrayList<>();
        HUMAN_FACING.stream()
                .filter(element::hasAttribute)
                .forEach(attribute -> {
                    final String value = element.getAttribute(attribute);
                    used.add(value);
                    if (!value.startsWith(KEY_PREFIX)) {
                        findings.add(PolicyFinding.inFile(named, LITERAL_STRING,
                                element.getTagName() + "/" + attribute + " is the sentence \""
                                        + value + "\" rather than a dictionary key"));
                    }
                });
        return findings;
    }

    /**
     * What one element breaks, where it is something a person operates or reads.
     *
     * @param named how a finding names the file
     * @param element the element
     * @return one finding per rule it breaks
     */
    private static List<PolicyFinding> controlFindings(String named, Element element) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final String resourceType = element.getAttribute("sling:resourceType");
        if (CONTROLS.stream().anyMatch(resourceType::endsWith)
                && NAMES.stream().noneMatch(element::hasAttribute)) {
            findings.add(PolicyFinding.inFile(named, ACCESSIBLE_NAME, element.getTagName()
                    + " is operated and nothing names it, so it is announced as its own tag name"));
        }
        if (resourceType.endsWith("/table") && !hasHeaders(element)) {
            findings.add(PolicyFinding.inFile(named, HEADER_ASSOCIATION, element.getTagName()
                    + " declares no columns, so every cell in it belongs to no header"));
        }
        if (resourceType.contains("image") && !element.hasAttribute("alt")) {
            findings.add(PolicyFinding.inFile(named, TEXT_ALTERNATIVE, element.getTagName()
                    + " is an image with nothing to read instead of it"));
        }
        if (HOVER_REVEALED.stream().anyMatch(revealed -> resourceType.contains(revealed)
                || element.getAttribute("granite:class").contains(revealed))
                && CONTROLS.stream().anyMatch(control -> descendantResourceTypes(element).stream()
                        .anyMatch(nested -> nested.endsWith(control)))) {
            findings.add(PolicyFinding.inFile(named, HOVER_ONLY, element.getTagName()
                    + " holds a control that only a pointing device reveals"));
        }
        return findings;
    }

    private static boolean hasHeaders(Element table) {
        return childrenOf(table).stream()
                .filter(child -> COLUMNS.equals(child.getTagName()))
                .anyMatch(columns -> childrenOf(columns).stream()
                        .anyMatch(column -> column.hasAttribute("jcr:title")));
    }

    private static List<String> descendantResourceTypes(Element element) {
        final List<String> types = new ArrayList<>();
        childrenOf(element).forEach(child -> {
            types.add(child.getAttribute("sling:resourceType"));
            types.addAll(descendantResourceTypes(child));
        });
        return types;
    }

    /**
     * Every key the dictionary declares.
     *
     * @param dictionary where the dictionary sits
     * @return the keys, in the order the dictionary declares them
     */
    public static Set<String> declaredKeys(Path dictionary) {
        final Set<String> keys = new LinkedHashSet<>();
        elementsIn(parseXml(dictionary)).stream()
                .filter(element -> element.hasAttribute("sling:key"))
                .forEach(element -> keys.add(element.getAttribute("sling:key")));
        return keys;
    }

    /**
     * Every key a data source names, which is the other place a console string is decided.
     *
     * @param root the repository root
     * @return the keys, in the order the sources name them
     */
    private static List<String> usedInSources(Path root) {
        final List<String> keys = new ArrayList<>();
        RepositoryTree.filesUnder(root.resolve(SOURCES), ".java").forEach(source ->
                RepositoryTree.text(source).lines().forEach(line -> {
                    int at = line.indexOf('"' + KEY_PREFIX);
                    while (at >= 0) {
                        final int end = line.indexOf('"', at + 1 + KEY_PREFIX.length());
                        if (end < 0) {
                            return;
                        }
                        keys.add(line.substring(at + 1, end));
                        at = line.indexOf('"' + KEY_PREFIX, end);
                    }
                }));
        return keys;
    }

    private static List<Element> elementsIn(Document document) {
        final List<Element> elements = new ArrayList<>();
        collect(document.getDocumentElement(), elements);
        return elements;
    }

    private static void collect(Node node, List<Element> elements) {
        if (node instanceof final Element element) {
            elements.add(element);
        }
        IntStream.range(0, node.getChildNodes().getLength())
                .mapToObj(index -> node.getChildNodes().item(index))
                .forEach(child -> collect(child, elements));
    }

    private static List<Element> childrenOf(Element element) {
        return IntStream.range(0, element.getChildNodes().getLength())
                .mapToObj(index -> element.getChildNodes().item(index))
                .filter(Element.class::isInstance)
                .map(Element.class::cast)
                .toList();
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
            throw new IllegalStateException(file + " is not a document this reader can parse",
                    failure);
        }
    }
}
