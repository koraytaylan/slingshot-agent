// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.ArtifactDescriptor;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the package went, and nothing else.
 *
 * <p>A package is bytes by its nature, so this answer is a reference to them rather than a
 * description of what went in. The filter the package was built with is inside the package, which
 * is the one place it cannot disagree with the package: a filter reported beside the artifact would
 * be a second account of the same decision, and the day the two differed nobody could tell which
 * the package was actually built from.</p>
 */
public final class DownloadContentPackageResult {

    private DownloadContentPackageResult() {
    }

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(ArtifactDescriptor.ARGUMENT_MEMBER,
            ArtifactDescriptor.BYTE_LENGTH, ArtifactDescriptor.DIGEST,
            ArtifactDescriptor.IDENTIFIER, ArtifactDescriptor.MEDIA_TYPE, ArtifactDescriptor.SLOT,
            ArtifactDescriptor.SUGGESTED_FILE_NAME);

    /** What kind of file a package is, which is what a reader is told to expect. */
    public static final String MEDIA_TYPE = "application/zip";

    /** What a package's file is called, given its name. */
    public static final String FILE_SUFFIX = ".zip";

    /**
     * The result one package produces.
     *
     * @param published where the package went and what it is
     * @param identifier the artifact's own identifier
     * @param packageName what the package is called
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(OverflowPublication.Published published,
                                                   String identifier, String packageName) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(ArtifactDescriptor.ARGUMENT_MEMBER, ArtifactDescriptor.documentOf(published,
                identifier, MEDIA_TYPE, packageName + FILE_SUFFIX));
        return new DocumentValue.Mapping(result);
    }
}
