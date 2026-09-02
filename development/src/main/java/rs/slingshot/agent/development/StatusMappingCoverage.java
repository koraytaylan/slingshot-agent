// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the committed failure mapping and the protocol's own categories are the same set.
 *
 * <p>Both directions, because both mistakes are real. A category with no row is a refusal this
 * agent cannot render at all, which somebody would find at the worst moment; a row for no category
 * is a status somebody chose for something that no longer exists, which reads as a decision and is
 * a leftover.</p>
 */
public final class StatusMappingCoverage {

    /** Where the mapping this compares against is committed. */
    public static final String MAPPING_FILE = "policy/failure-status-mapping.toml";

    /** Where the categories are declared, in the product's own source. */
    public static final String CATEGORY_SOURCE =
            "core/src/main/java/rs/slingshot/agent/wire/ErrorCode.java";

    /** How a category's own spelling opens where it is declared. */
    private static final String SPELLING_OPENS = "(\"";

    private StatusMappingCoverage() {
    }

    /**
     * Every finding comparing the committed mapping with the declared categories.
     *
     * @param root the repository root
     * @return one finding per category with no row and per row naming no category
     */
    public static PolicyReport against(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<String> declared = declaredCategories(root);
        final List<String> mapped = mappedCategories(root);
        declared.stream()
                .filter(category -> !mapped.contains(category))
                .map(category -> PolicyFinding.inFile(MAPPING_FILE, "uncovered-category",
                        category + " is a category this build declares and the mapping does not"
                                + " name, so a refusal in it cannot be rendered at all"))
                .forEach(findings::add);
        mapped.stream()
                .filter(category -> !declared.contains(category))
                .map(category -> PolicyFinding.inFile(MAPPING_FILE, "unknown-category",
                        category + " is a row for a category this build does not declare"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * The categories the protocol declares, read from the source that declares them.
     *
     * @param root the repository root
     * @return the spellings, in the order they are declared
     */
    public static List<String> declaredCategories(Path root) {
        final List<String> declared = new ArrayList<>();
        RepositoryTree.text(root.resolve(CATEGORY_SOURCE)).lines()
                .map(String::strip)
                .filter(line -> line.contains(SPELLING_OPENS))
                .filter(line -> !line.startsWith("*") && !line.startsWith("//"))
                .forEach(line -> {
                    final int opening = line.indexOf(SPELLING_OPENS);
                    final int value = opening + SPELLING_OPENS.length();
                    final int closing = line.indexOf('"', value);
                    if (opening > 0 && closing > value) {
                        declared.add(line.substring(value, closing));
                    }
                });
        return List.copyOf(declared);
    }

    /**
     * The categories the committed mapping names, in the order it names them.
     *
     * @param root the repository root
     * @return the categories
     */
    public static List<String> mappedCategories(Path root) {
        final List<String> mapped = new ArrayList<>();
        RepositoryTree.text(root.resolve(MAPPING_FILE)).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("category = "))
                .forEach(line -> mapped.add(line.replace("category = ", "").replace("\"", "")));
        return List.copyOf(mapped);
    }
}
