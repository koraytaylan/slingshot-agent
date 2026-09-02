// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which content one caller wants packaged, and what to call the package.
 *
 * <p>Three lists rather than one list of decorated roots. The roots say where to start; the
 * inclusions and exclusions say which of what is under them belongs in the package. Keeping them
 * apart is what makes "everything under here except the private part" expressible without every
 * root carrying a disposition that is redundant on most of them.</p>
 *
 * <p>A filter is a pattern rather than a path, and it is compiled when the argument is read rather
 * than while the package is being built. A pattern that will not compile is a malformed question,
 * and finding that out after a subtree has been walked is finding it out too late.</p>
 *
 * @param packageName what the package is called, which is also what a reader saves it as
 * @param roots where to start, of which there is at least one
 * @param inclusionFilters which of what is under the roots belongs in, empty meaning all of it
 * @param exclusionFilters which of that is left out, applied after the inclusions
 */
public record DownloadContentPackageCommand(String packageName, List<String> roots,
                                            List<String> inclusionFilters,
                                            List<String> exclusionFilters) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "download_content_package";

    /** The member the roots are carried in. */
    public static final String ROOTS = "roots";

    /** The member the package's name is carried in. */
    public static final String PACKAGE_NAME = "package_name";

    /** The member the inclusion patterns are carried in. */
    public static final String INCLUSION_FILTERS = "inclusion_filters";

    /** The member the exclusion patterns are carried in. */
    public static final String EXCLUSION_FILTERS = "exclusion_filters";

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS =
            List.of(EXCLUSION_FILTERS, INCLUSION_FILTERS, PACKAGE_NAME, ROOTS);

    /** The members a caller has to send; a package with no name and no roots is not a package. */
    public static final List<String> REQUIRED = List.of(PACKAGE_NAME, ROOTS);

    /** What a package's name may be made of, which is what a file name may be made of. */
    public static final Pattern NAME_SHAPE = Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Holds the three lists apart from whatever the caller still has a reference to. */
    public DownloadContentPackageCommand {
        roots = List.copyOf(roots);
        inclusionFilters = List.copyOf(inclusionFilters);
        exclusionFilters = List.copyOf(exclusionFilters);
    }

    /**
     * Where this package starts.
     *
     * @return the roots, which nothing may add to
     */
    @Override
    public List<String> roots() {
        return Collections.unmodifiableList(roots);
    }

    /**
     * Which of what is under the roots belongs in.
     *
     * @return the patterns, which nothing may add to
     */
    @Override
    public List<String> inclusionFilters() {
        return Collections.unmodifiableList(inclusionFilters);
    }

    /**
     * Which of that is left out.
     *
     * @return the patterns, which nothing may add to
     */
    @Override
    public List<String> exclusionFilters() {
        return Collections.unmodifiableList(exclusionFilters);
    }

    /**
     * Whether one path belongs in this package.
     *
     * <p>Under a root, matching an inclusion where any were named, and matching no exclusion. The
     * exclusions are applied last on purpose: a caller writes "everything under here" and then
     * carves pieces out of it, and an exclusion that could be overridden by an inclusion would make
     * the order of two lists decide what a package contains.</p>
     *
     * @param path the path
     * @return whether it belongs
     */
    public boolean contains(String path) {
        if (roots.stream().noneMatch(root -> path.equals(root) || path.startsWith(root + "/"))) {
            return false;
        }
        if (exclusionFilters.stream().anyMatch(pattern -> matches(pattern, path))) {
            return false;
        }
        return inclusionFilters.isEmpty()
                || inclusionFilters.stream().anyMatch(pattern -> matches(pattern, path));
    }

    private static boolean matches(String pattern, String path) {
        return Pattern.compile(pattern).matcher(path).find();
    }

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The roots are not a list of absolute paths. */
        ROOTS_NOT_PATHS,
        /** No root was named, so the package would be of nothing. */
        NO_ROOTS,
        /** More roots were named than this deployment packages at once. */
        TOO_MANY_ROOTS,
        /** The name is empty, too long, or made of something a file name is not. */
        NAME_REJECTED,
        /** A filter is not a list of patterns. */
        FILTERS_NOT_PATTERNS,
        /** More filters were named than this deployment evaluates. */
        TOO_MANY_FILTERS,
        /** A filter will not compile, which is a malformed question rather than an empty answer. */
        FILTER_NOT_A_PATTERN
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(DownloadContentPackageCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the name, the roots and the filters
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object with roots and a name in it");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final Optional<String> absent = REQUIRED.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; this command"
                    + " chooses neither what to package nor what to call it for a caller");
        }
        return named(mapping, contract);
    }

    private static Outcome named(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(PACKAGE_NAME).orElseThrow() instanceof final DocumentValue.Text name)) {
            return new Refused(Refusal.NAME_REJECTED, PACKAGE_NAME + " is not text");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PACKAGE_NAME_BYTES);
        if (name.value().length() > bound) {
            return new Refused(Refusal.NAME_REJECTED,
                    PACKAGE_NAME + " is longer than the " + bound + " a package's name may be");
        }
        if (!NAME_SHAPE.matcher(name.value()).matches()) {
            return new Refused(Refusal.NAME_REJECTED, PACKAGE_NAME + " is what a reader saves this"
                    + " package as, so it is made of letters, digits, hyphens and underscores and"
                    + " nothing else");
        }
        return rooted(name.value(), mapping, contract);
    }

    private static Outcome rooted(String name, DocumentValue.Mapping mapping,
                                  AgentContract contract) {
        if (!(mapping.member(ROOTS).orElseThrow() instanceof final DocumentValue.Sequence asked)) {
            return new Refused(Refusal.ROOTS_NOT_PATHS, ROOTS + " is a list of absolute paths");
        }
        if (asked.items().isEmpty()) {
            return new Refused(Refusal.NO_ROOTS, ROOTS + " is empty, so this package would be of"
                    + " nothing at all");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PACKAGE_ROOTS);
        if (asked.items().size() > bound) {
            return new Refused(Refusal.TOO_MANY_ROOTS, asked.items().size() + " roots is more than"
                    + " the " + bound + " this deployment packages at once");
        }
        final List<String> roots = asked.items().stream()
                .filter(item -> item instanceof DocumentValue.Text)
                .map(item -> ((DocumentValue.Text) item).value())
                .toList();
        if (roots.size() != asked.items().size()
                || roots.stream().anyMatch(root -> root.isEmpty() || root.charAt(0) != '/')) {
            return new Refused(Refusal.ROOTS_NOT_PATHS,
                    ROOTS + " holds something that is not an absolute path");
        }
        return filtered(name, roots, mapping, contract);
    }

    private static Outcome filtered(String name, List<String> roots, DocumentValue.Mapping mapping,
                                    AgentContract contract) {
        final Filters inclusions = patterns(mapping, INCLUSION_FILTERS,
                contract.value(ContractLimit.MAXIMUM_PACKAGE_INCLUSION_EXPRESSIONS));
        if (inclusions instanceof final Rejected rejected) {
            return rejected.refusal();
        }
        final Filters exclusions = patterns(mapping, EXCLUSION_FILTERS,
                contract.value(ContractLimit.MAXIMUM_PACKAGE_EXCLUSION_EXPRESSIONS));
        if (exclusions instanceof final Rejected rejected) {
            return rejected.refusal();
        }
        return new Held(new DownloadContentPackageCommand(name, roots,
                ((Compiled) inclusions).patterns(), ((Compiled) exclusions).patterns()));
    }

    /** What reading one filter list produced: the patterns, or the reason there are none. */
    private sealed interface Filters permits Compiled, Rejected {
    }

    /**
     * A filter list this command takes.
     *
     * @param patterns the patterns, every one of which compiles
     */
    private record Compiled(List<String> patterns) implements Filters {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    private record Rejected(Refused refusal) implements Filters {
    }

    /**
     * One filter list, read and compiled.
     *
     * @param mapping the argument document
     * @param member which list
     * @param bound how many patterns that list may hold
     * @return the patterns, or the one reason there are none
     */
    private static Filters patterns(DocumentValue.Mapping mapping, String member, long bound) {
        final Optional<DocumentValue> asked = mapping.member(member);
        if (asked.isEmpty()) {
            return new Compiled(List.of());
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence held)) {
            return new Rejected(new Refused(Refusal.FILTERS_NOT_PATTERNS,
                    member + " is a list of patterns"));
        }
        if (held.items().size() > bound) {
            return new Rejected(new Refused(Refusal.TOO_MANY_FILTERS, held.items().size()
                    + " patterns is more than the " + bound + " this deployment evaluates"));
        }
        final List<String> patterns = held.items().stream()
                .filter(item -> item instanceof DocumentValue.Text)
                .map(item -> ((DocumentValue.Text) item).value())
                .toList();
        if (patterns.size() != held.items().size()) {
            return new Rejected(new Refused(Refusal.FILTERS_NOT_PATTERNS,
                    member + " holds something that is not a pattern"));
        }
        return compiled(member, patterns);
    }

    private static Filters compiled(String member, List<String> patterns) {
        for (final String pattern : patterns) {
            try {
                Pattern.compile(pattern);
            } catch (final PatternSyntaxException malformed) {
                return new Rejected(new Refused(Refusal.FILTER_NOT_A_PATTERN, member + " holds a"
                        + " pattern that will not compile, which is a malformed question rather"
                        + " than a filter that matches nothing: " + malformed.getDescription()));
            }
        }
        return new Compiled(patterns);
    }
}
