// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every node this agent writes, read from the layout it commits rather than from the code that
 * writes them.
 *
 * <p>The layout is a document because two things have to agree with it: the paths this bundle
 * derives, and the tree a deployment's own initialisation script creates. A node written by code
 * that the script never creates is a node with no access-control entry; a node the script creates
 * that nothing writes is a node nobody can explain. Both are found by comparing against one
 * declaration rather than by comparing the two with each other.</p>
 */
public final class StateLayout {

    /** Where the layout is embedded in this bundle. */
    public static final String RESOURCE = "/rs/slingshot/agent/store/repository-layout.toml";

    /** Which of the two write primitives creates a node, or neither. */
    public enum Primitive {
        /** Created by claim-by-creation, which is how one writer among several wins. */
        CLAIM,
        /** Created and changed by compare-and-set. */
        SET,
        /** Created once by the repository initialisation script, and never by a request. */
        INITIALISATION
    }

    /**
     * One node the layout declares.
     *
     * @param name the node's own name
     * @param path where it sits, relative to the tree, with derived segments named in braces
     * @param primitive which primitive creates it
     * @param holds what it holds
     */
    public record Node(String name, String path, Primitive primitive, String holds) {
    }

    private final String root;
    private final long bucketDepth;
    private final long bucketCharacters;
    private final long childCeiling;
    private final List<Node> nodes;

    private StateLayout(String root, long bucketDepth, long bucketCharacters, long childCeiling,
                        List<Node> nodes) {
        this.root = root;
        this.bucketDepth = bucketDepth;
        this.bucketCharacters = bucketCharacters;
        this.childCeiling = childCeiling;
        this.nodes = nodes;
    }

    /** The result of reading it: the layout, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A layout that satisfied its shape completely.
     *
     * @param layout the loaded layout
     */
    public record Loaded(StateLayout layout) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * Reads the layout embedded in this bundle.
     *
     * @return the layout, or the one reason there is none
     */
    public static Outcome load() {
        try (InputStream stream = StateLayout.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return new Refused(RESOURCE + " is not embedded in this bundle");
            }
            return read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Reads a layout out of the document's own text.
     *
     * <p>Read line by line rather than through a parser: this bundle carries no document library,
     * and a layout is a list of rows whose shape is decided here anyway. A row this reader cannot
     * make sense of is a refusal rather than a row it guesses at.</p>
     *
     * @param document the layout document
     * @return the layout, or the one reason there is none
     */
    public static Outcome read(String document) {
        final List<String> lines = document.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        final List<Node> nodes = nodesIn(lines);
        final String root = stated(lines, "root").orElse("");
        if (root.isEmpty() || nodes.isEmpty()) {
            return new Refused("the layout declares no root or no node");
        }
        return new Loaded(new StateLayout(root,
                number(stated(lines, "depth").orElse("")),
                number(stated(lines, "characters").orElse("")),
                number(stated(lines, "child_ceiling").orElse("")), nodes));
    }

    private static List<Node> nodesIn(List<String> lines) {
        final List<Node> nodes = new ArrayList<>();
        final List<String> pending = new ArrayList<>();
        lines.forEach(line -> {
            if (line.startsWith("[")) {
                addNode(nodes, pending);
                pending.clear();
                return;
            }
            pending.add(line);
        });
        addNode(nodes, pending);
        return nodes;
    }

    private static Optional<String> stated(List<String> lines, String key) {
        return lines.stream()
                .filter(line -> key.equals(keyOf(line)))
                .map(StateLayout::valueOf)
                .findFirst();
    }

    private static void addNode(List<Node> nodes, List<String> pending) {
        final Optional<String> name = value(pending, "name");
        final Optional<String> path = value(pending, "path");
        final Optional<String> primitive = value(pending, "primitive");
        final Optional<String> holds = value(pending, "holds");
        if (name.isEmpty() || path.isEmpty() || primitive.isEmpty() || holds.isEmpty()) {
            return;
        }
        nodes.add(new Node(name.get(), path.get(),
                Primitive.valueOf(primitive.get().toUpperCase(java.util.Locale.ROOT)), holds.get()));
    }

    private static Optional<String> value(List<String> pending, String key) {
        return pending.stream()
                .filter(line -> key.equals(keyOf(line)))
                .map(StateLayout::valueOf)
                .findFirst();
    }

    private static String keyOf(String line) {
        return line.contains(" = ") ? line.substring(0, line.indexOf(" = ")).strip() : "";
    }

    private static String valueOf(String line) {
        final String stated = line.substring(line.indexOf(" = ") + " = ".length()).strip();
        return stated.startsWith("\"") ? stated.substring(1, stated.length() - 1) : stated;
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * The tree this agent writes.
     *
     * @return the root
     */
    public String root() {
        return root;
    }

    /**
     * How many levels of bucket a derivation has.
     *
     * @return the depth
     */
    public long bucketDepth() {
        return bucketDepth;
    }

    /**
     * How many characters each level of bucket takes.
     *
     * @return the count
     */
    public long bucketCharacters() {
        return bucketCharacters;
    }

    /**
     * How many children a node may have before this layout stops claiming it is bucketed.
     *
     * @return the ceiling
     */
    public long childCeiling() {
        return childCeiling;
    }

    /**
     * Every node this layout declares.
     *
     * @return the nodes, in the layout's own order
     */
    public List<Node> nodes() {
        return java.util.Collections.unmodifiableList(nodes);
    }

    /**
     * The node one name declares.
     *
     * @param name the node's own name
     * @return the node, or nothing where the layout declares no such node
     */
    public Optional<Node> node(String name) {
        return nodes.stream()
                .filter(node -> node.name().equals(name))
                .findFirst();
    }
}
