// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything a caller can reach, and what each of those may not do.
 *
 * <p>This product runs inside somebody else's author, with a service user, in the same process as
 * their content. That combination is the shape of a privilege escalation, and every guard against
 * it was put in earlier — so this is the part that says whether all of them still hold, derived
 * from the built packages and the registry rather than from a list, because the surface somebody
 * forgets to add to a list is the one nobody attacks.</p>
 *
 * <p>The strongest of these is the simplest: there is no impersonation call anywhere in either
 * bundle. A guard that refuses impersonation can be got round; a product that contains no way to
 * impersonate has nothing to get round, and the difference is the one that survives somebody
 * writing a new command in a hurry.</p>
 */
public final class EscalationSurface {

    /** Where the routes a caller can reach are declared. */
    public static final String ROUTES = "policy/agent-routes.toml";

    /** Where one row per command is declared. */
    public static final String REGISTRY = "policy/commands";

    /** Where the console's own resources sit. */
    public static final String CONSOLE =
            "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console";

    /** The rule a way to run as somebody else is reported under. */
    public static final String AN_IMPERSONATION_EXISTS = "an-impersonation-exists";

    /** The rule a command that does other than its access class says is reported under. */
    public static final String ACCESS_CLASS_MISMATCH = "access-class-mismatch";

    /** The rule a read with room to work that obtains a session is reported under. */
    public static final String A_STAGING_READ_OBTAINS_A_SESSION =
            "a-staging-read-obtains-a-session";

    /** The rule a command with no handler to attack is reported under. */
    public static final String A_COMMAND_WITH_NO_HANDLER = "a-command-with-no-handler";

    /** Every way of running as somebody else, refused by there being none rather than by a guard. */
    private static final List<String> IMPERSONATIONS =
            List.of("impersonate", "Impersonation", "loginAdministrative", "SimpleCredentials");

    /** Where a product source lives, which is what this is asked of. */
    private static final List<String> PRODUCT_SOURCES =
            List.of("core/src/main/java", "aem/src/main/java");

    /** What a handler is called, so one can be found for a row without a list. */
    private static final String HANDLER_SUFFIX = "Handler.java";

    /** What a commit looks like, which is what a read may never contain. */
    private static final List<String> COMMITS =
            List.of("session.commit()", "resolver.commit()", "ONE_COMMIT");

    /**
     * The categories a row declares when what it changes is not the caller's repository.
     *
     * <p>Read from the row rather than derived from the handler's package, exactly as the
     * one-commit wrapper reads it. A command that offers content to replication changes a queue the
     * platform owns: it is a write to a caller and owes no commit, and a rule that demanded one
     * would be demanding a write nobody asked for.</p>
     */
    private static final List<String> OWES_NO_COMMIT =
            List.of("admission_outcome_unknown", "platform_control_outcome_unknown");

    /**
     * How a handler reaches something that commits somewhere else.
     *
     * <p>A write to the caller's own repository commits where you can see it. A write to a platform
     * the agent only asks — a user directory, a workflow engine, a replication queue — commits
     * behind a seam, and the handler that asked has no commit in it at all. Both are writes to a
     * caller, so both satisfy the rule; what neither may do is claim to be a read.</p>
     */
    private static final String PLATFORM_SEAM = "rs.slingshot.agent.command.platform.";

    /** How a session is obtained, which a read with room to work may never do. */
    private static final List<String> SESSION_ACQUISITION =
            List.of("loginService", "getServiceResourceResolver", "loginAdministrative");

    private EscalationSurface() {
    }

    /**
     * Everything a caller can reach, derived rather than listed.
     *
     * @param routes every route the committed table declares
     * @param commands every command the registry declares
     * @param consoleResources every console resource the package carries
     */
    public record Surface(List<String> routes, List<String> commands,
                          List<String> consoleResources) {

        /** Holds a surface nothing can change afterwards. */
        public Surface {
            routes = List.copyOf(routes);
            commands = List.copyOf(commands);
            consoleResources = List.copyOf(consoleResources);
        }

        /**
         * How many things there are to attack, which is what a suite reports it covered.
         *
         * @return the count
         */
        public int size() {
            return routes.size() + commands.size() + consoleResources.size();
        }
    }

    /**
     * What a caller can reach, read out of the committed table, the registry and the package.
     *
     * @param root the repository root
     * @return the surface
     */
    public static Surface of(Path root) {
        return new Surface(ScenarioInventory.routeNames(root.resolve(ROUTES)),
                commandsIn(root), consoleResourcesIn(root));
    }

    /**
     * The whole surface, held to everything that has to be true of all of it.
     *
     * @param root the repository root
     * @return one finding per direction that is not closed
     */
    public static PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(impersonationFindings(root));
        findings.addAll(accessClassFindings(root));
        return PolicyReport.of(findings);
    }

    /**
     * Every place a way to run as somebody else exists, of which there should be none.
     *
     * @param root the repository root
     * @return one finding per place
     */
    private static List<PolicyFinding> impersonationFindings(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        PRODUCT_SOURCES.forEach(tree ->
                RepositoryTree.filesUnder(root.resolve(tree), ".java").forEach(source -> {
                    final String held = withoutComments(RepositoryTree.text(source));
                    IMPERSONATIONS.stream()
                            .filter(held::contains)
                            .forEach(way -> findings.add(PolicyFinding.inFile(
                                    root.relativize(source).toString(), AN_IMPERSONATION_EXISTS,
                                    way + " is a way to run as somebody else, and a product that"
                                            + " contains none has nothing to get round")));
                }));
        return findings;
    }

    /**
     * Every command whose behaviour and declared access class disagree.
     *
     * <p>A read that commits is the escalation this whole design exists to prevent, and a write
     * that does not is a command reporting a change nobody made. A read with room to work is held
     * to one rule more, because a scratch directory is exactly where a read would grow into a
     * write.</p>
     *
     * @param root the repository root
     * @return one finding per disagreement
     */
    private static List<PolicyFinding> accessClassFindings(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        rowsIn(root).forEach(row -> {
            final Optional<Path> found = handlerFor(root, row.wireName());
            if (found.isEmpty()) {
                findings.add(PolicyFinding.inFile(REGISTRY + "/" + row.wireName() + ".toml",
                        A_COMMAND_WITH_NO_HANDLER,
                        row.wireName() + " has a row and no handler to attack"));
                return;
            }
            final Path handler = found.orElseThrow();
            final String held = withoutComments(RepositoryTree.text(handler));
            final boolean commits = COMMITS.stream().anyMatch(held::contains);
            final boolean writesSomewhere = commits || held.contains(PLATFORM_SEAM);
            if ("read".equals(row.accessClass()) && commits) {
                findings.add(PolicyFinding.inFile(root.relativize(handler).toString(),
                        ACCESS_CLASS_MISMATCH, row.wireName() + " declares read and commits"));
            }
            if ("write".equals(row.accessClass()) && !writesSomewhere && row.owesACommit()) {
                findings.add(PolicyFinding.inFile(root.relativize(handler).toString(),
                        ACCESS_CLASS_MISMATCH, row.wireName() + " declares write and commits"
                                + " nothing, so it reports a change nobody made"));
            }
            if (row.stagingBytes() > 0
                    && SESSION_ACQUISITION.stream().anyMatch(held::contains)) {
                findings.add(PolicyFinding.inFile(root.relativize(handler).toString(),
                        A_STAGING_READ_OBTAINS_A_SESSION, row.wireName() + " has room to work and"
                                + " obtains a session, which is where a read grows into a write"));
            }
        });
        return findings;
    }

    /**
     * One registry row, read as the three things this check is about.
     *
     * @param wireName the command
     * @param accessClass what it says it does
     * @param stagingBytes how much room it declared, which is none for every command but one
     * @param failureCategories every way it may fail, which is where it says what it changes
     */
    public record Row(String wireName, String accessClass, long stagingBytes,
                      String failureCategories) {

        /**
         * Whether this command owes a commit at all.
         *
         * <p>A write whose row declares an admission's or a platform control's unknown outcome
         * changes something that is not the caller's repository, and owes none. Read from the row
         * because that is where the difference is written down.</p>
         *
         * @return whether it does
         */
        public boolean owesACommit() {
            return OWES_NO_COMMIT.stream().noneMatch(failureCategories::contains);
        }
    }

    /**
     * Every registry row, read from the directory rather than from a list.
     *
     * @param root the repository root
     * @return the rows, in the directory's own order
     */
    public static List<Row> rowsIn(Path root) {
        final List<Row> rows = new ArrayList<>();
        RepositoryTree.filesUnder(root.resolve(REGISTRY), ".toml").forEach(file -> {
            final String held = RepositoryTree.text(file);
            rows.add(new Row(String.valueOf(file.getFileName()).replace(".toml", ""),
                    valueOf(held, "access"), Long.parseLong(numberOf(held, "staging_bytes")),
                    held.lines().filter(line -> line.startsWith("failure_categories = "))
                            .findFirst().orElse("")));
        });
        return rows;
    }

    private static List<String> commandsIn(Path root) {
        return rowsIn(root).stream().map(Row::wireName).toList();
    }

    private static List<String> consoleResourcesIn(Path root) {
        final Path console = root.resolve(CONSOLE);
        if (!Files.isDirectory(console)) {
            return List.of();
        }
        return RepositoryTree.filesUnder(console, ".content.xml").stream()
                .map(file -> root.relativize(file.getParent()).toString().replace('\\', '/'))
                .sorted()
                .toList();
    }

    /**
     * The handler one row's command runs through.
     *
     * <p>Found by name first and by neighbourhood second, because several commands share one
     * handler where they are the same operation on different subjects — the six that add and remove
     * members of a group are one handler, and a lookup that insisted on one handler per row would
     * report six commands as unattacked when all six are attacked through the same code.</p>
     *
     * @param root the repository root
     * @param wireName the command
     * @return where its handler sits, or nothing where none does
     */
    private static Optional<Path> handlerFor(Path root, String wireName) {
        final List<Path> handlers = PRODUCT_SOURCES.stream()
                .flatMap(tree -> RepositoryTree.filesUnder(root.resolve(tree), HANDLER_SUFFIX)
                        .stream())
                .toList();
        final String expected = handlerName(wireName);
        final Optional<Path> named = handlers.stream()
                .filter(file -> expected.equals(String.valueOf(file.getFileName())))
                .findFirst();
        return named.isPresent() ? named : beside(handlers, root, wireName);
    }

    /**
     * The handler beside whichever source spells this command's wire name.
     *
     * @param handlers every handler in the product
     * @param root the repository root
     * @param wireName the command
     * @return the handler in the same package, or nothing where the name is spelled nowhere
     */
    private static Optional<Path> beside(List<Path> handlers, Path root, String wireName) {
        return PRODUCT_SOURCES.stream()
                .flatMap(tree -> RepositoryTree.filesUnder(root.resolve(tree), ".java").stream())
                .filter(source -> RepositoryTree.text(source).contains("\"" + wireName + "\""))
                .map(Path::getParent)
                .filter(java.util.Objects::nonNull)
                .flatMap(directory -> handlers.stream()
                        .filter(handler -> directory.equals(handler.getParent())))
                .findFirst();
    }

    /**
     * What a command's handler is called, derived from its wire name.
     *
     * @param wireName the command
     * @return the file name its handler would have
     */
    public static String handlerName(String wireName) {
        final StringBuilder named = new StringBuilder();
        for (final String part : wireName.split("_")) {
            named.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return named + "Handler.java";
    }

    private static String valueOf(String document, String key) {
        return document.lines()
                .filter(line -> line.startsWith(key + " = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElse("");
    }

    private static String numberOf(String document, String key) {
        return document.lines()
                .filter(line -> line.startsWith(key + " = "))
                .map(line -> line.substring(line.indexOf('=') + 1).trim())
                .findFirst()
                .orElse("0");
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .lines()
                .map(line -> line.indexOf("//") >= 0 ? line.substring(0, line.indexOf("//")) : line)
                .reduce("", (all, line) -> all + line + "\n");
    }
}
