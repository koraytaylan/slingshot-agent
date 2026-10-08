// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/** Canonical child addresses and a final property name, without parent traversal. */
final class PredicatePropertyPath {

    /** The first sibling has no written index. */
    private static final long FIRST_WRITTEN_SIBLING = 2;
    /** Last character of the initial ASCII control block. */
    private static final int LAST_ASCII_CONTROL = 31;
    /** The separate ASCII delete control. */
    private static final int ASCII_DELETE = 127;

    private PredicatePropertyPath() {
    }

    /**
     * Tests a canonical child address ending in exactly one property name.
     * @param path the relative property address
     * @param contract the authenticated name and address bounds
     * @return whether the address resolves entirely below its candidate
     */
    static boolean relative(String path, AgentContract contract) {
        if (!within(path, ContractLimit.MAXIMUM_RELATIVE_PROPERTY_PATH_BYTES, contract)) {
            return false;
        }
        final int slash = path.lastIndexOf('/');
        final String property = path.substring(slash + 1);
        return within(property, ContractLimit.MAXIMUM_PROPERTY_NAME_BYTES, contract)
                && name(property, contract) && (slash < 0 || Arrays.stream(path.substring(0, slash)
                        .split("/", -1)).allMatch(part -> segment(part, contract)));
    }

    /**
     * Tests an absolute repository value without normalizing or aliasing its segments.
     * @param path the absolute address
     * @param contract the authenticated name, segment and byte bounds
     * @return whether the value is already canonical
     */
    static boolean absolute(String path, AgentContract contract) {
        if (!path.startsWith("/") || !within(path, ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES, contract)) {
            return false;
        }
        if ("/".equals(path)) {
            return true;
        }
        final String[] parts = path.substring(1).split("/", -1);
        return parts.length <= contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_SEGMENTS)
                && Arrays.stream(parts).allMatch(part -> segment(part, contract));
    }

    private static boolean segment(String part, AgentContract contract) {
        final int opening = part.indexOf('[');
        if (opening < 0) {
            return name(part, contract);
        }
        if (!part.endsWith("]") || !name(part.substring(0, opening), contract)) {
            return false;
        }
        final String index = part.substring(opening + 1, part.length() - 1);
        if (!index.matches("[1-9][0-9]*")) {
            return false;
        }
        try {
            final long value = Long.parseLong(index);
            return value >= FIRST_WRITTEN_SIBLING
                    && value <= contract.value(ContractLimit.MAXIMUM_SAME_NAME_SIBLING_INDEX);
        } catch (final NumberFormatException outOfRange) {
            return false;
        }
    }

    private static boolean name(String value, AgentContract contract) {
        if (!within(value, ContractLimit.MAXIMUM_REPOSITORY_NAME_BYTES, contract)
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            return false;
        }
        final int colon = value.indexOf(':');
        if (colon < 0) {
            return local(value);
        }
        return value.substring(0, colon).matches("[A-Za-z_][A-Za-z0-9_.-]*")
                && local(value.substring(colon + 1));
    }

    private static boolean local(String value) {
        return !value.isEmpty() && !".".equals(value) && !"..".equals(value)
                && !value.startsWith(" ") && !value.endsWith(" ")
                && value.codePoints().noneMatch(character -> character <= LAST_ASCII_CONTROL
                        || character == ASCII_DELETE
                        || "/:[]*|".indexOf(character) >= 0);
    }

    private static boolean within(String value, ContractLimit bound, AgentContract contract) {
        return !value.isEmpty() && value.getBytes(StandardCharsets.UTF_8).length <= contract.value(bound);
    }
}
