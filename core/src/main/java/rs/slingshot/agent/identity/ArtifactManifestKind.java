// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a command declares it will produce, before it is sent.
 *
 * <p>The set is the client's, spelled the way the client spells it, because these spellings are
 * inside a digest both sides derive. A fourth kind here would be a fourth kind nothing else knows
 * about, and a different spelling would be the same submission with two different idempotency
 * keys.</p>
 */
public enum ArtifactManifestKind {

    /** The command produces no artifact at all. */
    EMPTY("empty"),

    /** The command loads content and produces one artifact. */
    LOAD("load"),

    /** The command builds a package and produces several. */
    PACKAGE("package");

    private final String spelling;

    ArtifactManifestKind(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this kind is spelled inside a derived digest.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The kind one spelling names.
     *
     * @param spelling the spelling
     * @return the kind, or nothing where no kind is spelled that way
     */
    public static Optional<ArtifactManifestKind> named(String spelling) {
        return Arrays.stream(values())
                .filter(kind -> kind.spelling.equals(spelling))
                .findFirst();
    }
}
