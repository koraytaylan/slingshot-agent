// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * How a result points at bytes it did not carry.
 *
 * <p>Two commands answer this and more will: a subtree too large to send inline, and a package
 * which is bytes by its nature. Written once because the client declares one descriptor for all of
 * them, and two renderings of one document are two places for it to drift — the day one of them
 * spelled the digest member differently, a reader would silently stop verifying.</p>
 *
 * <p>The digest is the artifact's own, and it travels so a reader can verify the bytes for itself
 * rather than trusting the transfer that delivered them.</p>
 */
public final class ArtifactDescriptor {

    private ArtifactDescriptor() {
    }

    /** The member the artifact's own identifier is carried in. */
    public static final String IDENTIFIER = "identifier";

    /** The member the slot it was published into is carried in. */
    public static final String SLOT = "slot";

    /** The member its media type is carried in. */
    public static final String MEDIA_TYPE = "media_type";

    /** The member its size is carried in. */
    public static final String BYTE_LENGTH = "byte_length";

    /** The member its digest is carried in, which a reader verifies for itself. */
    public static final String DIGEST = "digest";

    /** The member the name a reader should save it under is carried in. */
    public static final String SUGGESTED_FILE_NAME = "suggested_file_name";

    /** The member a result carries one of these in. */
    public static final String ARGUMENT_MEMBER = "artifact";

    /** Every member a descriptor has, and there is no seventh. */
    public static final List<String> MEMBERS =
            List.of(BYTE_LENGTH, DIGEST, IDENTIFIER, MEDIA_TYPE, SLOT, SUGGESTED_FILE_NAME);

    /**
     * The descriptor one published artifact has.
     *
     * @param published where it went and what it is
     * @param identifier its own identifier
     * @param mediaType what kind of file it is
     * @param fileName the name a reader should save it under
     * @return the descriptor document
     */
    public static DocumentValue.Mapping documentOf(OverflowPublication.Published published,
                                                   String identifier, String mediaType,
                                                   String fileName) {
        final SequencedMap<String, DocumentValue> descriptor = new LinkedHashMap<>();
        descriptor.put(IDENTIFIER, new DocumentValue.Text(identifier));
        descriptor.put(SLOT, new DocumentValue.Text(published.slot()));
        descriptor.put(MEDIA_TYPE, new DocumentValue.Text(mediaType));
        descriptor.put(BYTE_LENGTH, new DocumentValue.Whole(published.delivery().byteCount()));
        descriptor.put(DIGEST, new DocumentValue.Text(published.delivery().digest().rendered()));
        descriptor.put(SUGGESTED_FILE_NAME, new DocumentValue.Text(fileName));
        return new DocumentValue.Mapping(descriptor);
    }
}
