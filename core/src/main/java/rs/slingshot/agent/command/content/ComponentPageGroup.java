// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;

/** One open page's bounded component witnesses; nested pages use separate groups. */
final class ComponentPageGroup {

    private final String path;
    private final List<String> wanted;
    private final MatchMode mode;
    private final SequencedMap<String, String> sources = new LinkedHashMap<>();

    /**
     * Begins one group using only detached, validated request values.
     * @param path the nearest page's address
     * @param wanted requested component types, already bounded by the command parser
     * @param mode whether one or every requested type is required
     */
    ComponentPageGroup(String path, List<String> wanted, MatchMode mode) {
        this.path = path;
        this.wanted = List.copyOf(wanted);
        this.mode = mode;
    }

    /**
     * Reads a currently readable node only while this page still needs a witness.
     * Completed groups keep their first witnesses for current-authority validation at emission.
     * @param resource the contributing node, used only during this call
     */
    void match(Resource resource) {
        if (qualifies()) {
            return;
        }
        final String type = resource.getValueMap()
                .get(FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY, "");
        if (wanted.contains(type)) {
            sources.putIfAbsent(type, resource.getPath());
        }
    }

    /** Whether the completed page's observed types satisfy the requested mode.
     * @return whether current-authority proof should be attempted
     */
    boolean qualifies() {
        return mode == MatchMode.ALL ? sources.keySet().containsAll(wanted) : !sources.isEmpty();
    }

    /** The nearest page represented by this group.
     * @return its detached address
     */
    String path() {
        return path;
    }

    /** Freezes witnesses after this page's own content has been examined.
     * @param maximumDepth the authenticated retained traversal bound
     * @return an immutable proof, with no request resolver or resource retained
     */
    ComponentPageEvidence evidence(int maximumDepth) {
        return new ComponentPageEvidence(path, sources, maximumDepth);
    }
}
