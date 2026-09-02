// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one repository path publishes as.
 *
 * <p>Both halves are answered: the path that was asked about and the address it maps to. A caller
 * comparing the echoed path against the one they sent catches the whole class of defect where an
 * answer is right about some other path.</p>
 */
public final class MapResourcePathResult {

    private MapResourcePathResult() {
    }

    /** The member the path that was asked about is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the address it publishes as is carried in. */
    public static final String MAPPED_ADDRESS = "mapped_address";

    /** The member the rules the mapping went through are carried in, where asked for. */
    public static final String TRACE = "trace";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(MAPPED_ADDRESS, REPOSITORY_PATH, TRACE);

    /**
     * The result one mapping produces.
     *
     * @param repositoryPath the path that was asked about
     * @param mappedAddress what it publishes as
     * @param trace the rules it went through, which is empty where none were asked for
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath, String mappedAddress,
                                                   List<String> trace) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        result.put(MAPPED_ADDRESS, new DocumentValue.Text(mappedAddress));
        if (!trace.isEmpty()) {
            result.put(TRACE, new DocumentValue.Sequence(
                    Collections.unmodifiableList(trace).stream()
                            .map(rule -> (DocumentValue) new DocumentValue.Text(rule))
                            .toList()));
        }
        return new DocumentValue.Mapping(result);
    }
}
