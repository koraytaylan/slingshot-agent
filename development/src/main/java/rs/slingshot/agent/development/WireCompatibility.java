// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * What every document on the wire looks like now, against what it looked like when it was decided.
 *
 * <p>A protocol change nobody noticed is a client that stops working, and the moment it is cheap to
 * notice is the moment it is introduced. So every document kind's canonical bytes and every
 * command's contract identity are snapshotted, and a difference is a build failure naming the
 * document and the bytes rather than a surprise somebody's client reports.</p>
 *
 * <p>Byte-exact rather than structural, deliberately. Two documents that mean the same thing and
 * are not the same bytes are two different digests, and the five-field identity is a comparison of
 * digests — so a structurally equivalent change is exactly the change that breaks a client while
 * looking like it changed nothing.</p>
 *
 * <p>Changing a snapshot takes a version increment on whatever it covers, which is what makes it a
 * decision rather than a regenerated file. A snapshot somebody can refresh by running a command is
 * a snapshot that records what the code does rather than what was agreed.</p>
 */
public final class WireCompatibility {

    /** Where the snapshots sit, one file per thing that goes on the wire. */
    public static final String SNAPSHOTS = "compatibility/wire";

    /** Where the vectors every document kind is exercised by sit. */
    public static final String VECTORS = "schemas/agent-protocol-vectors.json";

    /** Where one row per command is declared. */
    public static final String REGISTRY = "policy/commands";

    /** What the registry's own snapshot is called. */
    public static final String REGISTRY_SNAPSHOT = "registry-identity.txt";

    /** The rule a document kind with no snapshot is reported under. */
    public static final String A_KIND_WITH_NO_SNAPSHOT = "a-kind-with-no-snapshot";

    /** The rule a snapshot naming a kind that no longer exists is reported under. */
    public static final String A_SNAPSHOT_WITH_NO_KIND = "a-snapshot-with-no-kind";

    /** The rule a document whose bytes have changed is reported under. */
    public static final String THE_BYTES_CHANGED = "the-bytes-changed";

    /** The rule a change carrying no version increment is reported under. */
    public static final String CHANGED_WITHOUT_A_VERSION_INCREMENT =
            "changed-without-a-version-increment";

    /** What separates the fields of one registry line. */
    private static final String BETWEEN = "\t";

    private WireCompatibility() {
    }

    /**
     * Every document kind the vectors exercise, and the bytes each one accepts.
     *
     * <p>Read from the vectors rather than from a list, because the vectors are what the client's
     * own suite is written against: a kind that has a vector is a kind a client sends, and a kind
     * that does not is one nobody has agreed the shape of.</p>
     *
     * @param root the repository root
     * @return the accepted bytes by kind, in the vectors' own order
     */
    public static SequencedMap<String, String> currentDocuments(Path root) {
        final SequencedMap<String, String> documents = new LinkedHashMap<>();
        final String vectors = RepositoryTree.text(root.resolve(VECTORS));
        int at = vectors.indexOf("{\"id\":");
        while (at >= 0) {
            final String kind = memberOf(vectors, at, "\"kind\":\"");
            final String expected = memberOf(vectors, at, "\"expected\":\"");
            if (!kind.isEmpty() && !expected.isEmpty() && !documents.containsKey(kind)) {
                documents.put(kind, expected);
            }
            at = vectors.indexOf("{\"id\":", at + 1);
        }
        return documents;
    }

    /**
     * Every command's contract identity, as one line each.
     *
     * <p>One line per command rather than one file, because what matters is that the set changed
     * rather than which member of it: a client compares the whole identity, so a change to any
     * field of any row is one change to what this agent is.</p>
     *
     * @param root the repository root
     * @return the lines, in the registry's own order
     */
    public static List<String> currentRegistryIdentity(Path root) {
        final List<String> lines = new ArrayList<>();
        RepositoryTree.filesUnder(root.resolve(REGISTRY), ".toml").forEach(file -> {
            final String held = RepositoryTree.text(file);
            lines.add(String.join(BETWEEN,
                    String.valueOf(file.getFileName()).replace(".toml", ""),
                    valueOf(held, "contract_version"),
                    valueOf(held, "contract_limits_digest"),
                    valueOf(held, "argument_schema_digest"),
                    valueOf(held, "result_schema_digest")));
        });
        return List.copyOf(lines);
    }

    /**
     * Everything the build produces now against everything that was decided.
     *
     * @param root the repository root
     * @return one finding per difference, per kind with no snapshot, and per snapshot with no kind
     */
    public static PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final SequencedMap<String, String> documents = currentDocuments(root);
        documents.forEach((kind, bytes) -> {
            final Path snapshot = root.resolve(SNAPSHOTS).resolve(kind + ".canonical");
            if (!Files.isRegularFile(snapshot)) {
                findings.add(PolicyFinding.inFile(SNAPSHOTS, A_KIND_WITH_NO_SNAPSHOT,
                        kind + " goes on the wire and nothing recorded what it looks like"));
                return;
            }
            final String recorded = RepositoryTree.text(snapshot).stripTrailing();
            if (!recorded.equals(bytes)) {
                findings.add(PolicyFinding.inFile(SNAPSHOTS + "/" + kind + ".canonical",
                        THE_BYTES_CHANGED, kind + " was " + recorded + " and is now " + bytes));
            }
        });
        findings.addAll(strandedSnapshots(root, documents));
        findings.addAll(registryFindings(root));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> strandedSnapshots(Path root,
                                                         SequencedMap<String, String> documents) {
        final Path snapshots = root.resolve(SNAPSHOTS);
        if (!Files.isDirectory(snapshots)) {
            return List.of();
        }
        return RepositoryTree.filesUnder(snapshots, ".canonical").stream()
                .map(file -> String.valueOf(file.getFileName()).replace(".canonical", ""))
                .filter(kind -> !documents.containsKey(kind))
                .sorted()
                .map(kind -> PolicyFinding.inFile(SNAPSHOTS + "/" + kind + ".canonical",
                        A_SNAPSHOT_WITH_NO_KIND,
                        kind + " is recorded and nothing on the wire is that any more"))
                .toList();
    }

    /**
     * Whether the registry's identity set changed, and whether the change was decided.
     *
     * <p>A changed line whose version is the same is the one that matters: it is a client told the
     * contract is what it was, receiving something else.</p>
     *
     * @param root the repository root
     * @return one finding per changed line
     */
    private static List<PolicyFinding> registryFindings(Path root) {
        final Path snapshot = root.resolve(SNAPSHOTS).resolve(REGISTRY_SNAPSHOT);
        final List<String> current = currentRegistryIdentity(root);
        if (!Files.isRegularFile(snapshot)) {
            return List.of(PolicyFinding.inFile(SNAPSHOTS + "/" + REGISTRY_SNAPSHOT,
                    A_KIND_WITH_NO_SNAPSHOT,
                    "the registry's contract identity set is not recorded at all"));
        }
        final List<String> recorded = RepositoryTree.text(snapshot).lines()
                .filter(line -> !line.isBlank())
                .toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        current.stream()
                .filter(line -> !recorded.contains(line))
                .forEach(line -> findings.add(PolicyFinding.inFile(
                        SNAPSHOTS + "/" + REGISTRY_SNAPSHOT,
                        versionIncremented(line, recorded) ? THE_BYTES_CHANGED
                                : CHANGED_WITHOUT_A_VERSION_INCREMENT,
                        line.split(BETWEEN)[0] + " is not what was recorded: " + line)));
        recorded.stream()
                .filter(line -> !current.contains(line))
                .filter(line -> current.stream()
                        .noneMatch(now -> now.startsWith(line.split(BETWEEN)[0] + BETWEEN)))
                .forEach(line -> findings.add(PolicyFinding.inFile(
                        SNAPSHOTS + "/" + REGISTRY_SNAPSHOT, A_SNAPSHOT_WITH_NO_KIND,
                        line.split(BETWEEN)[0] + " is recorded and the registry no longer has it")));
        return findings;
    }

    /**
     * Whether a changed line carries a version other than the one that was recorded for it.
     *
     * @param line the line as it is now
     * @param recorded every line as it was
     * @return whether the version moved
     */
    private static boolean versionIncremented(String line, List<String> recorded) {
        final String[] fields = line.split(BETWEEN);
        return recorded.stream()
                .filter(was -> was.startsWith(fields[0] + BETWEEN))
                .findFirst()
                .map(was -> !was.split(BETWEEN)[1].equals(fields[1]))
                .orElse(true);
    }

    /**
     * What this build would record, so a recording step writes it and a check reads it.
     *
     * @param root the repository root
     * @return the whole snapshot set, by file name
     */
    public static SequencedMap<String, String> recordable(Path root) {
        final SequencedMap<String, String> files = new LinkedHashMap<>();
        currentDocuments(root).forEach((kind, bytes) -> files.put(kind + ".canonical", bytes));
        files.put(REGISTRY_SNAPSHOT, String.join("\n", currentRegistryIdentity(root)));
        return files;
    }

    private static String memberOf(String document, int from, String member) {
        final int at = document.indexOf(member, from);
        if (at < 0) {
            return "";
        }
        final int opens = at + member.length();
        return document.substring(opens, closingQuote(document, opens));
    }

    /**
     * Where one quoted value ends, which is the first quote nothing escaped.
     *
     * @param document the whole document
     * @param from where the value begins
     * @return where it ends
     */
    private static int closingQuote(String document, int from) {
        return java.util.stream.IntStream.range(from, document.length())
                .filter(at -> document.charAt(at) == '"' && document.charAt(at - 1) != '\\')
                .findFirst()
                .orElse(document.length());
    }

    private static String valueOf(String document, String key) {
        return document.lines()
                .filter(line -> line.startsWith(key + " = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElse("");
    }

    /**
     * How a snapshot file's bytes are written, so the recording and the reading agree.
     *
     * @param content what to write
     * @return the bytes
     */
    public static byte[] bytesOf(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
