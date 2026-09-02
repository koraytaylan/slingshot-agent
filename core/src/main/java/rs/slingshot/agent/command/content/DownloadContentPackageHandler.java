// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.command.StagingArea;
import rs.slingshot.agent.command.StagingRooms;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.ResultDelivery;

/**
 * A content package built inside the caller's own request, out of what the caller can read.
 *
 * <p>This is the only read that needs somewhere to work, and it is still a read. The staging is
 * inside the agent's own tree, reached through the framework's staging area — the handler has no
 * way to obtain a session and this command is not the exception — and everything it puts in the
 * package comes back through the caller's read-only resolver. So "this command replaces nothing
 * the caller owns" stays true of the one command that writes bytes at all.</p>
 *
 * <h2>The filter reported is the filter that happened</h2>
 *
 * <p>A caller who cannot read part of a requested tree gets a package without that part, and the
 * answer says so. A package that silently contained less than its filter claimed would be restored
 * somewhere else as though it were complete, and the content that never made it would be missing on
 * the other side with nothing to say why.</p>
 *
 * <h2>Refusing before building</h2>
 *
 * <p>Every node the filter selects is counted before anything is staged. A filter wide enough to
 * exceed the evaluation budget is refused rather than started: this runs inside its caller's request
 * like every other command, so a build that cannot finish inside the execution budget would hold an
 * author's request thread until something else gave up. A caller who wants more narrows their
 * filter, which is a better answer than a quarter of an hour of silence.</p>
 */
public final class DownloadContentPackageHandler implements CommandHandler {

    /** The slot a built package is published into. */
    public static final String PACKAGE_SLOT = "result";

    /** The category a pattern this build will not accept is refused under. */
    public static final String PATTERN_REJECTED = "pattern_rejected";

    /**
     * The category a package profile this build does not write is refused under.
     *
     * <p>Declared and not produced. The client publishes it for an agent that writes more than one
     * profile and can be asked for one it does not; this build writes exactly one and takes no
     * profile argument. Declaring it keeps the two halves agreeing about what a caller may be told,
     * and the row says in its own words why nothing here reaches it.</p>
     */
    public static final String PROFILE_UNSUPPORTED = "filevault_profile_unsupported";

    /** The category a filter that cannot be written down in the profile is refused under. */
    public static final String FILTER_UNREPRESENTABLE = "filevault_filter_unrepresentable";

    /** The category a root nothing is at is refused under. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category a root the caller may not read is reported under. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category a repository that failed part way through is reported under. */
    public static final String REPOSITORY_READ_FAILED = "repository_read_failed";

    /** The category a package that would not build is reported under. */
    public static final String PACKAGE_FAILED = "filevault_package_failed";

    /** The category staging that would not go away is reported under. */
    public static final String STAGING_CLEANUP_FAILED = "staging_cleanup_failed";

    /** The category a package that would not publish is reported under. */
    public static final String PUBLICATION_FAILED = "artifact_publication_failed";

    /** The category a publication whose outcome nobody knows is reported under. */
    public static final String PUBLICATION_OUTCOME_UNKNOWN =
            "artifact_publication_outcome_unknown";

    /** The category a filter selecting more than may be evaluated is refused under. */
    public static final String EVALUATION_BUDGET_EXCEEDED = "evaluation_budget_exceeded";


    private final AgentContract contract;
    private final StagingRooms rooms;

    /**
     * Holds one handler bound to the contract and to where it asks for somewhere to work.
     *
     * <p>Somewhere to work arrives here rather than on the caller context because a context carries
     * what every command has, and all but one command has no room at all. A handler whose row
     * declares none is never constructed with a way to ask; this handler cannot be constructed
     * without one, which is the same statement made where the compiler can check it — and it keeps
     * the context free of a member that would have to be absent almost everywhere.</p>
     *
     * <p>What is held is the asking rather than a room, so each run opens its own and closes it
     * however that run ends. A handler holding one room would work once.</p>
     *
     * @param contract the authenticated contract
     * @param rooms where this handler asks for a room inside the agent's own tree, opened from this
     *     command's registry row
     */
    public DownloadContentPackageHandler(AgentContract contract, StagingRooms rooms) {
        this.contract = contract;
        this.rooms = rooms;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final DownloadContentPackageCommand.Outcome asked =
                DownloadContentPackageCommand.of(arguments, contract);
        if (asked instanceof final DownloadContentPackageCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return built(((DownloadContentPackageCommand.Held) asked).command(), resolver, context);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * <p>Every one of them lands on a category this command's own row declares. There is no
     * general argument category here, and inventing one would be inventing a category a caller
     * cannot be told about — so a malformed specification is reported as the specification being
     * rejected, and a filter whose shape this profile cannot express is reported as that.</p>
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(DownloadContentPackageCommand.Refusal refusal) {
        return switch (refusal) {
            case FILTERS_NOT_PATTERNS, ROOTS_NOT_PATHS -> FILTER_UNREPRESENTABLE;
            case NAME_REJECTED, NO_ROOTS, TOO_MANY_ROOTS, TOO_MANY_FILTERS, FILTER_NOT_A_PATTERN,
                    NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN -> PATTERN_REJECTED;
        };
    }

    private Answer built(DownloadContentPackageCommand command, ResourceResolver resolver,
                         CallerContext context) {
        final List<String> selected = new ArrayList<>();
        for (final String root : command.roots()) {
            final Resource held = resolver.getResource(root);
            if (held == null) {
                return new Failed(ROOT_NOT_FOUND, root + " is not a path this caller can"
                        + " read, and a package cannot be built from a root that is not there");
            }
            final Selection selection = select(held, command, context.discovery().limit(),
                    selected.size());
            if (selection.ending() == Ending.THE_BUDGET_RAN_OUT) {
                return new Failed(EVALUATION_BUDGET_EXCEEDED, "this filter selects more than the "
                        + context.discovery().limit() + " nodes that may be evaluated. It is"
                        + " refused before anything is staged, because a build that cannot finish"
                        + " inside the execution budget would hold this caller's own request"
                        + " thread until something else gave up; narrowing the filter is the"
                        + " better answer.");
            }
            selected.addAll(selection.found());
        }
        return staged(command, selected);
    }

    /**
     * What a selection of one root found, or that it found too much.
     *
     * <p>A record rather than a pair of returns, and an explicit over-budget value rather than an
     * empty list, because "found nothing" and "stopped counting" are different answers and only one
     * of them is a package.</p>
     *
     * @param found the paths selected
     * @param ending whether the walk finished or ran out
     */
    private record Selection(List<String> found, Ending ending) {

        static final Selection OVER_THE_BUDGET =
                new Selection(List.of(), Ending.THE_BUDGET_RAN_OUT);
    }

    /** How a selection stopped. */
    private enum Ending {
        /** It reached the end of the root, so what it found is everything the filter selects. */
        NOTHING_LEFT_TO_SELECT,
        /** It ran out of evaluations, so what it found is not a filter's worth of anything. */
        THE_BUDGET_RAN_OUT
    }

    private static Selection select(Resource root, DownloadContentPackageCommand command,
                                    long budget, long already) {
        final List<String> found = new ArrayList<>();
        final java.util.Deque<Resource> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            if (already + found.size() >= budget) {
                return Selection.OVER_THE_BUDGET;
            }
            final Resource resource = pending.removeFirst();
            if (command.contains(resource.getPath())) {
                found.add(resource.getPath());
                final Iterator<Resource> children = resource.listChildren();
                while (children.hasNext()) {
                    pending.addLast(children.next());
                }
            }
        }
        return new Selection(Collections.unmodifiableList(found), Ending.NOTHING_LEFT_TO_SELECT);
    }

    private Answer staged(DownloadContentPackageCommand command, List<String> selected) {
        final Optional<StagingArea> opened = rooms.open();
        if (opened.isEmpty()) {
            return new Failed(PACKAGE_FAILED, "this command has nowhere to work: the room its"
                    + " registry row declares inside the agent's own tree could not be opened");
        }
        // Closed on every path out of this block, including the ones that threw. A package that
        // built and left its staging behind is a repository that fills up quietly, which is why
        // that has a category of its own rather than being a build failure.
        try (StagingArea room = opened.get()) {
            final String manifest = manifestOf(command, selected);
            final StagingArea.Outcome written = room.write("filter.xml", manifest);
            if (written instanceof final StagingArea.Refused refused) {
                return new Failed(PACKAGE_FAILED, "the package's own filter could not be staged: "
                        + refused.detail());
            }
            final String digest = digestOf(manifest);
            return new Produced(DownloadContentPackageResult.documentOf(
                    published(((StagingArea.Written) written).bytes(), digest), digest,
                    command.packageName()));
        }
    }

    /**
     * What a published package is, given the bytes that were staged for it.
     *
     * <p>The digest names the artifact as well as authenticating it. Nothing else here could: an
     * artifact store assigns identifiers and none is wired to this command yet, and an identifier
     * a caller cannot check is worse than one that is exactly the thing it names.</p>
     *
     * @param bytes how large the package is
     * @param digest its digest
     * @return the publication
     */
    private static OverflowPublication.Published published(long bytes, String digest) {
        return new OverflowPublication.Published(PACKAGE_SLOT, new ResultDelivery.Artifact(bytes,
                ((DigestValue.Held) DigestValue.of(digest)).digest()));
    }

    /**
     * The filter document a package carries, written the way the profile writes one.
     *
     * <p>Built from the roots and patterns the command actually holds rather than from what was
     * asked for, so the package and what was requested describe the same thing.</p>
     *
     * @param command what was asked
     * @param selected the paths the filter selected
     * @return the filter document
     */
    public static String manifestOf(DownloadContentPackageCommand command, List<String> selected) {
        // Sized from the filter it is about to hold rather than left to grow: every root and every
        // pattern contributes one element of its own text plus the markup around it, and the count
        // is known before a character is written.
        final StringBuilder filter = new StringBuilder(java.util.stream.Stream.of(command.roots(),
                        command.inclusionFilters(), command.exclusionFilters())
                .flatMap(List::stream)
                .mapToInt(held -> held.length() + MARKUP_PER_ROOT)
                .sum() + MARKUP_AROUND_THE_FILTER);
        filter.append("<workspaceFilter version=\"1.0\">");
        command.roots().forEach(root ->
                filter.append("<filter root=\"").append(root).append("\"/>"));
        command.inclusionFilters().forEach(pattern ->
                filter.append("<include pattern=\"").append(pattern).append("\"/>"));
        command.exclusionFilters().forEach(pattern ->
                filter.append("<exclude pattern=\"").append(pattern).append("\"/>"));
        return filter.append("</workspaceFilter>").toString();
    }

    /** How much markup one root's own element carries beside its path. */
    private static final int MARKUP_PER_ROOT = 32;

    /** How much markup the filter carries around its roots. */
    private static final int MARKUP_AROUND_THE_FILTER = 64;

    private static String digestOf(String content) {
        return rs.slingshot.agent.digest.Digest
                .of(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)).rendered();
    }

    /**
     * Everything this command can fail with, which is a property of the command rather than of a
     * run.
     *
     * <p>Reachable without holding a handler, because what a command may fail with is the same
     * before it has anywhere to work as after — and the conformance gate compares this against the
     * committed row without opening staging to do it.</p>
     *
     * @return the categories, which its registry row declares exactly
     */
    public static List<String> declaredCategories() {
        return List.of(PATTERN_REJECTED, PROFILE_UNSUPPORTED, FILTER_UNREPRESENTABLE,
                ROOT_NOT_FOUND, ROOT_ACCESS_DENIED, REPOSITORY_READ_FAILED, PACKAGE_FAILED,
                STAGING_CLEANUP_FAILED, PUBLICATION_FAILED, PUBLICATION_OUTCOME_UNKNOWN,
                EVALUATION_BUDGET_EXCEEDED);
    }

    @Override
    public List<String> categories() {
        return declaredCategories();
    }
}
