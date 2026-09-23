// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Repository names as FileVault writes them into a package's file tree.
 *
 * <p>A namespace prefix becomes an underscore on each side, so {@code jcr:content} is the
 * directory {@code _jcr_content}; a character a file system will not hold is written as a percent
 * sign and its code; and a plain name that already looks like an escaped prefix gets one more
 * underscore, so reading the tree back can only ever mean one name.</p>
 */
final class PlatformNames {

    /** The characters a platform file name may not hold as they are. */
    private static final String RESERVED = "\\/:*?\"<>|%";

    /** The first character a file name may hold as itself. */
    private static final char FIRST_PRINTABLE = ' ';

    private PlatformNames() {
    }

    /**
     * One repository name as a platform file name.
     *
     * @param name the repository name
     * @return the file name
     */
    static String of(String name) {
        final int colon = name.indexOf(':');
        if (colon > 0) {
            return "_" + escaped(name.substring(0, colon)) + "_"
                    + escaped(name.substring(colon + 1));
        }
        if (name.startsWith("_") && name.indexOf('_', 1) > 0) {
            return "_" + escaped(name);
        }
        return escaped(name);
    }

    /**
     * One absolute repository path as the platform path under a package's content root.
     *
     * @param path the absolute repository path
     * @return the platform path, beginning with a separator
     */
    static String pathOf(String path) {
        return Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isEmpty())
                .map(PlatformNames::of)
                .collect(Collectors.joining("/", "/", ""));
    }

    private static String escaped(String name) {
        final StringBuilder escaped = new StringBuilder(name.length());
        for (final char character : name.toCharArray()) {
            if (character < FIRST_PRINTABLE || RESERVED.indexOf(character) >= 0) {
                escaped.append('%').append(String.format(java.util.Locale.ROOT, "%02x",
                        (int) character));
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
