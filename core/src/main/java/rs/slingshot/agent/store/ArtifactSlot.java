// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;

/**
 * One place an operation may publish bytes to, named so that two writers cannot mean the same one.
 *
 * <p>A slot is a path segment, so what it may be spelled with is what a path segment may be spelled
 * with. That is not fussiness: a slot carrying a separator would put an artifact under a different
 * operation, and a slot carrying a parent reference would put it outside the agent's tree
 * altogether — and both would be a command choosing where somebody else's bytes go.</p>
 *
 * @param name the slot's own name, as it is written
 */
public record ArtifactSlot(String name) {

    /** The slot this build publishes an oversized result into, as the client spells it. */
    public static final String RESULT_SLOT = "result";

    /** The slot a load command declares for a document too large to answer with. */
    public static final String LOADED_CONTENT_SLOT = "loaded_content_json";

    /** What kind of file that slot carries, which the client compares exactly. */
    public static final String LOADED_CONTENT_MEDIA_TYPE = "application/json";

    /** The slot a built content package occupies. */
    public static final String CONTENT_PACKAGE_SLOT = "content_package";

    /** What kind of file that slot carries, which the client compares exactly. */
    public static final String CONTENT_PACKAGE_MEDIA_TYPE = "application/zip";

    /** What kind of file a canonical JSON artifact carries, whatever slot it sits in. */
    public static final String CANONICAL_JSON_MEDIA_TYPE = "application/json";

    /**
     * What kind of bytes one slot holds.
     *
     * <p>These are the client's own spellings and it compares them exactly, so a slot answered
     * with another type is a fetch the client refuses rather than a transfer it accepts. The
     * answer is the slot's rather than a route's, because one route serves every slot and the
     * client's expectation is per-slot.</p>
     *
     * @param name the slot's own name
     * @return the media type
     */
    public static String mediaType(String name) {
        return switch (name) {
            case LOADED_CONTENT_SLOT -> LOADED_CONTENT_MEDIA_TYPE;
            case CONTENT_PACKAGE_SLOT -> CONTENT_PACKAGE_MEDIA_TYPE;
            default -> CANONICAL_JSON_MEDIA_TYPE;
        };
    }

    /** Why a slot name is not one this build will write to. */
    public enum Refusal {
        /** It is empty, and a slot nothing can name is one nothing can find again. */
        EMPTY,
        /** It carries a path separator, which would put the bytes somewhere else entirely. */
        CARRIES_A_SEPARATOR,
        /** It carries a parent reference, which would climb out of the agent's own tree. */
        CLIMBS_OUT_OF_THE_TREE,
        /** It carries something a name does not. */
        NOT_A_NAME
    }

    /** The result of reading a slot name: one this build writes to, or why it does not. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A slot this build will write to.
     *
     * @param slot the slot
     */
    public record Held(ArtifactSlot slot) implements Outcome {
    }

    /**
     * One it will not.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one slot name.
     *
     * @param name the name as the command spelled it
     * @return the slot, or the one reason there is none
     */
    public static Outcome of(String name) {
        if (name.isEmpty()) {
            return new Refused(Refusal.EMPTY,
                    "a slot nothing can name is one nothing can find again");
        }
        if (name.contains("..")) {
            return new Refused(Refusal.CLIMBS_OUT_OF_THE_TREE,
                    "a name carrying a parent reference would climb out of the agent's own tree");
        }
        if (name.contains("/")) {
            return new Refused(Refusal.CARRIES_A_SEPARATOR,
                    "a name carrying a separator would put the bytes somewhere else entirely");
        }
        if (name.chars().anyMatch(scalar -> !isNameCharacter(scalar))) {
            return new Refused(Refusal.NOT_A_NAME,
                    "a name carries letters, digits, hyphens, and underscores and nothing else");
        }
        return new Held(new ArtifactSlot(name));
    }

    private static boolean isNameCharacter(int scalar) {
        return scalar >= 'a' && scalar <= 'z' || scalar >= 'A' && scalar <= 'Z'
                || scalar >= '0' && scalar <= '9' || scalar == '-' || scalar == '_';
    }

    /**
     * Where one slot of one operation lives.
     *
     * @param operation the operation
     * @return the path
     */
    public StatePath under(StatePath operation) {
        return operation.child(ArtifactStore.NODE).child(name);
    }

    /**
     * The one reason a slot name was refused, where it was.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where there is a slot
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
