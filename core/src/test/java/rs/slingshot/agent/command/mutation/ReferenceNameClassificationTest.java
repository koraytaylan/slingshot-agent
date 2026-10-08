// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Identity-preserving classification includes every UTF-16 unit and encoding marker. */
final class ReferenceNameClassificationTest {
    @Test
    void everyUtf16UnitKeepsTheOriginalClassificationAtEachPosition() {
        IntStream.rangeClosed(Character.MIN_VALUE, Character.MAX_VALUE).forEach(symbol -> {
            final String text = Character.toString((char) symbol);
            List.of(text + "link", "link" + text, "li" + text + "nk").forEach(name ->
                    assertEquals(original(name), ReferenceValues.nativeName(name),
                            "UTF-16 unit " + symbol));
        });
    }

    @Test
    void encodingMarkersAndSupplementaryNamesKeepTheirExactIdentity() {
        List.of("", "link", "a_x", "_x0041_", "a_x0041_b", "a_X0041_b", "a_b-c.d9",
                "jcr:content", "plain_xplain", "link/child", "link\uD83D\uDE00",
                "\uD83D\uDE00link", "link\uD800", "link\uDC00").forEach(name ->
                assertEquals(original(name), ReferenceValues.nativeName(name)));
    }

    private static boolean original(String name) {
        return name.contains("_x") || name.codePoints().anyMatch(symbol -> symbol > 127
                || !Character.isLetterOrDigit(symbol) && symbol != '_' && symbol != '-' && symbol != '.');
    }
}
