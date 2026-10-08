// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.command.StagingArea;
import rs.slingshot.agent.command.StagingRooms;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.ArtifactSlot;
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
    public static final String PACKAGE_SLOT = ArtifactSlot.CONTENT_PACKAGE_SLOT;

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
            final String category = categoryFor(refused.refusal());
            final String detail = refused.refusal() + ": " + refused.detail();
            if (!PATTERN_REJECTED.equals(category)) {
                return new Failed(category, detail);
            }
            final Optional<DocumentValue.Mapping> named = rejectedPattern(arguments);
            return named.isPresent() ? new Failed(category, detail, new Stated(named.get()))
                    : new Failed(category, detail);
        }
        return built(((DownloadContentPackageCommand.Held) asked).command(), resolver, context);
    }

    /**
     * The refusal naming which filter was rejected, where the request carries one to name.
     *
     * <p>The client reads a rejected pattern as the collection it stood in and its position there,
     * and checks that position against its own request - so the refusal names the first
     * expression this build does not read as a selection, which is the one the caller has to
     * change. A request refused for another reason still has one filter to
     * name: the first, in the first collection that holds any.</p>
     *
     * @param arguments the argument document the caller sent
     * @return the refusal, or nothing where the request holds no filter at all
     */
    static Optional<DocumentValue.Mapping> rejectedPattern(DocumentValue.Mapping arguments) {
        final List<Map.Entry<String, List<String>>> collections = List.of(
                Map.entry(INCLUSION, texts(arguments,
                        DownloadContentPackageCommand.INCLUSION_FILTERS)),
                Map.entry(EXCLUSION, texts(arguments,
                        DownloadContentPackageCommand.EXCLUSION_FILTERS)));
        final Optional<DocumentValue.Mapping> uncompiled = collections.stream()
                .flatMap(collection -> java.util.stream.IntStream
                        .range(0, collection.getValue().size())
                        .filter(index -> !compiles(collection.getValue().get(index)))
                        .mapToObj(index -> patternRefusal(collection.getKey(), index)))
                .findFirst();
        if (uncompiled.isPresent()) {
            return uncompiled;
        }
        return collections.stream()
                .filter(collection -> !collection.getValue().isEmpty())
                .findFirst()
                .map(collection -> patternRefusal(collection.getKey(), 0));
    }

    private static List<String> texts(DocumentValue.Mapping arguments, String member) {
        return arguments.member(member)
                .filter(DocumentValue.Sequence.class::isInstance)
                .map(held -> ((DocumentValue.Sequence) held).items().stream()
                        .map(item -> item instanceof final DocumentValue.Text text
                                ? text.value() : "")
                        .toList())
                .orElseGet(List::of);
    }

    private static boolean compiles(String pattern) {
        return PackageSelection.of(pattern).isPresent();
    }

    private static DocumentValue.Mapping patternRefusal(String collection, long index) {
        final SequencedMap<String, DocumentValue> refusal = new LinkedHashMap<>();
        refusal.put("failure", new DocumentValue.Text(PATTERN_REJECTED));
        refusal.put("collection", new DocumentValue.Text(collection));
        refusal.put("expression_index", new DocumentValue.Whole(index));
        return new DocumentValue.Mapping(refusal);
    }

    /** How the client names the inclusion filters. */
    private static final String INCLUSION = "inclusion";

    /** How the client names the exclusion filters. */
    private static final String EXCLUSION = "exclusion";

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
        final rs.slingshot.agent.stream.ElapsedTime elapsed =
                rs.slingshot.agent.stream.ElapsedTime.start();
        for (final String root : command.roots()) {
            final Resource held = resolver.getResource(root);
            if (held == null) {
                return new Failed(ROOT_NOT_FOUND, root + " is not a path this caller can"
                        + " read, and a package cannot be built from a root that is not there");
            }
            final Selection selection = select(held, command, new Bounds(
                    context.discovery().limit(), context.time().limit(), elapsed), selected.size());
            if (selection.ending() == Ending.THE_BUDGET_RAN_OUT) {
                return new Failed(EVALUATION_BUDGET_EXCEEDED, "this filter selects more than the "
                        + context.discovery().limit() + " nodes that may be evaluated, or takes"
                        + " longer than the " + context.time().limit() + " milliseconds it may. It is"
                        + " refused before anything is staged, because a build that cannot finish"
                        + " inside the execution budget would hold this caller's own request"
                        + " thread until something else gave up; narrowing the filter is the"
                        + " better answer.");
            }
            selected.addAll(selection.found());
        }
        return staged(command, selected, resolver);
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

    /**
     * How much one package's selection may examine, and since when.
     *
     * @param nodes how many nodes may be evaluated across every root
     * @param milliseconds how long the selection may take
     * @param elapsed the monotonic duration shared by every selected root
     */
    private record Bounds(long nodes, long milliseconds,
                          rs.slingshot.agent.stream.ElapsedTime elapsed) {

        boolean spent(long evaluated) {
            return evaluated > nodes || elapsed.milliseconds() > milliseconds;
        }
    }

    private static Selection select(Resource root, DownloadContentPackageCommand command,
                                    Bounds bounds, long already) {
        final List<String> found = new ArrayList<>();
        final java.util.Deque<Iterator<Resource>> pending = new java.util.ArrayDeque<>();
        Optional<Resource> resource = Optional.of(root);
        long evaluated = 0;
        while (resource.isPresent()) {
            final Resource current = resource.orElseThrow();
            evaluated = evaluated + 1;
            if (bounds.spent(already + evaluated)) {
                return Selection.OVER_THE_BUDGET;
            }
            // An excluded path takes its whole subtree with it, so nothing beneath is visited. A
            // path that is merely not included is walked through only where an inclusion's anchor
            // could still lie below it.
            final String path = current.getPath();
            if (!command.excluded(path)) {
                final boolean included = command.included(path);
                if (included) {
                    found.add(path);
                }
                if (included || command.worthDescending(path)) {
                    pending.push(current.listChildren());
                }
            }
            resource = next(pending);
        }
        return new Selection(Collections.unmodifiableList(found), Ending.NOTHING_LEFT_TO_SELECT);
    }

    private static Optional<Resource> next(java.util.Deque<Iterator<Resource>> pending) {
        while (!pending.isEmpty()) {
            final Iterator<Resource> children = pending.peek();
            if (children.hasNext()) {
                return Optional.of(children.next());
            }
            pending.pop();
        }
        return Optional.empty();
    }

    private Answer staged(DownloadContentPackageCommand command, List<String> selected,
                          ResourceResolver resolver) {
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
            final byte[] packageBytes;
            try {
                packageBytes = packageBytes(command.packageName(), manifest, resolver, selected);
            } catch (final IOException invalidPackage) {
                return new Failed(PACKAGE_FAILED, "the package archive could not be built: "
                        + invalidPackage.getMessage());
            }
            final StagingArea.Outcome written = room.write("package.zip", packageBytes);
            if (written instanceof final StagingArea.Refused refused) {
                return new Failed(PACKAGE_FAILED, "the package archive could not be staged: "
                        + refused.detail());
            }
            final String digest = Digest.of(packageBytes).rendered();
            return new Artifact(DownloadContentPackageResult.documentOf(
                    published(((StagingArea.Written) written).bytes(), digest), digest,
                    command.packageName()), PACKAGE_SLOT, packageBytes);
        } catch (final UncheckedIOException cleanup) {
            return new Failed(STAGING_CLEANUP_FAILED,
                    "the package staging area could not be released: "
                            + cleanup.getMessage());
        }
    }

    /**
     * Builds an archive containing only the manifest.
     *
     * @param manifest the deterministic FileVault filter document
     * @return the archive bytes
     * @throws IOException if the archive cannot be written
     */
    static byte[] packageBytes(String manifest) throws IOException {
        return packageBytes(UNNAMED, manifest, archive -> { });
    }

    /**
     * Builds the deterministic archive, including each selected resource.
     *
     * @param manifest the deterministic FileVault filter document
     * @param resolver the caller's resolver used to read selected resources
     * @param selected resource paths to include
     * @return the archive bytes
     * @throws IOException if the archive cannot be written or a resource cannot be read
     */
    static byte[] packageBytes(String manifest, ResourceResolver resolver, List<String> selected)
            throws IOException {
        return packageBytes(UNNAMED, manifest, resolver, selected);
    }

    /**
     * Builds the deterministic archive of one named package, including each selected resource.
     *
     * @param name the package's own name, which its properties carry
     * @param manifest the deterministic FileVault filter document
     * @param resolver the caller's resolver used to read selected resources
     * @param selected resource paths to include
     * @return the archive bytes
     * @throws IOException if the archive cannot be written or a resource cannot be read
     */
    static byte[] packageBytes(String name, String manifest, ResourceResolver resolver,
                               List<String> selected) throws IOException {
        return packageBytes(name, manifest, archive -> {
            for (final String path : selected.stream().sorted().toList()) {
                writeContentEntry(archive, resolver, path);
            }
        });
    }

    /** What ends each line of a package's properties, whatever platform built it. */
    private static final String LINE = "\n";

    /** The name a package built without one carries, which only a suite builds. */
    private static final String UNNAMED = "package";

    /** The group every package this command builds is filed under. */
    static final String GROUP = "slingshot";

    /** The version every package this command builds carries, since each one is a snapshot. */
    static final String VERSION = "1.0.0";

    /** Where a package's content lives inside its archive. */
    static final String CONTENT_ROOT = "jcr_root";

    @FunctionalInterface
    private interface EntryWriter {

        void write(ZipOutputStream archive) throws IOException;
    }

    private static byte[] packageBytes(String name, String manifest, EntryWriter entries)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(entry("META-INF/vault/filter.xml"));
            archive.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(entry("META-INF/vault/properties.xml"));
            archive.write(propertiesOf(name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
            entries.write(archive);
        }
        return bytes.toByteArray();
    }

    /**
     * The properties a package manager reads a package's identity from.
     *
     * <p>A name, a group and a version, and nothing that changes between two builds of the same
     * content: no creation time and no builder, so identical content is identical bytes.</p>
     *
     * @param name the package's own name
     * @return the properties document
     */
    static String propertiesOf(String name) {
        return String.join(LINE, List.of(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>",
                "<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">",
                "<properties>",
                "<entry key=\"name\">" + escaped(name) + "</entry>",
                "<entry key=\"group\">" + GROUP + "</entry>",
                "<entry key=\"version\">" + VERSION + "</entry>",
                "</properties>", ""));
    }

    private static void writeContentEntry(ZipOutputStream archive, ResourceResolver resolver,
                                          String path) throws IOException {
        final Optional<javax.jcr.Node> node = Optional.ofNullable(resolver.getResource(path))
                .map(resource -> resource.adaptTo(javax.jcr.Node.class));
        if (node.isEmpty()) {
            return;
        }
        final String directory = CONTENT_ROOT + PlatformNames.pathOf(path);
        try {
            final DocumentView.Written written = DocumentView.of(node.get());
            archive.putNextEntry(entry(directory + "/.content.xml"));
            archive.write(written.document().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
            for (final Map.Entry<String, javax.jcr.Value> binary
                    : written.binaries().entrySet()) {
                archive.putNextEntry(entry(directory + "/" + binary.getKey()));
                final javax.jcr.Binary held = binary.getValue().getBinary();
                try (java.io.InputStream stream = held.getStream()) {
                    stream.transferTo(archive);
                } finally {
                    held.dispose();
                }
                archive.closeEntry();
            }
        } catch (final javax.jcr.RepositoryException unreadable) {
            throw new IOException(path + " could not be read: " + unreadable.getMessage(),
                    unreadable);
        }
    }

    private static ZipEntry entry(String name) {
        final ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        return entry;
    }

    private static String escaped(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
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
        // Each part is an element whose one value has already been escaped, and the document is
        // those parts in order, so nothing a caller wrote is ever read as markup.
        final List<String> parts = new ArrayList<>();
        parts.add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<workspaceFilter version=\"1.0\">");
        for (final String root : command.roots()) {
            parts.add(element("filter", "root", root, OPEN));
            final List<String> under = selected.stream()
                    .filter(path -> path.equals(root) || path.startsWith(root + "/"))
                    .toList();
            if (under.isEmpty()) {
                parts.add(element("include", "pattern", NEVER_MATCHES, CLOSED));
            }
            under.forEach(path -> parts.add(element("include", "pattern", exactly(path),
                    CLOSED)));
            parts.add("</filter>");
        }
        parts.add("</workspaceFilter>");
        return String.join("", parts);
    }

    /** An element that goes on to hold others. */
    private static final String OPEN = ">";

    /** An element that holds nothing. */
    private static final String CLOSED = "/>";

    /**
     * One element with one attribute, whose value is escaped before it is written.
     *
     * @param name the element's name
     * @param attribute the attribute's name
     * @param value the attribute's value, as text rather than markup
     * @param ending whether the element is open or closed
     * @return the element
     */
    private static String element(String name, String attribute, String value, String ending) {
        return "<" + name + " " + attribute + "=\"" + escaped(value) + "\"" + ending;
    }

    /**
     * One path as the FileVault pattern that matches it and nothing else.
     *
     * <p>FileVault reads a filter pattern as a regular expression, so the path is quoted rather
     * than written as it is: a dot or a bracket in a page name would otherwise be syntax. A literal
     * end-of-quote inside the path is broken up, because it would close the quoted region early
     * and turn the rest of the path into syntax after all.</p>
     *
     * @param path the path
     * @return the pattern
     */
    static String exactly(String path) {
        return "\\A\\Q" + path.replace("\\E", "\\E\\\\E\\Q") + "\\E\\z";
    }

    /** The pattern a root that selected nothing carries, so it installs nothing under it. */
    static final String NEVER_MATCHES = "\\A(?!)\\z";


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
