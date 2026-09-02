// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Optional;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one caller asked to load: an address and a depth, both of them theirs to state.
 *
 * <p>Neither has a default. A depth somebody else chose is a depth the caller did not, and the two
 * plausible defaults — one level, or everything — are a command that answers less than anybody
 * wanted and a command that walks a repository. A caller who has to write the depth down has
 * decided it; a caller who inherits one has not.</p>
 *
 * @param repositoryPath the absolute path of the subtree to read
 * @param depth how many generations below it to include, where zero is that node by itself
 */
public record LoadContentCommand(String repositoryPath, long depth) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "load_content_as_json";

    /** The member the address is carried in. */
    public static final String PATH = "path";

    /** The member the depth is carried in. */
    public static final String DEPTH = "depth";

    /** Every member this command's argument has, and there is no third. */
    public static final java.util.List<String> MEMBERS = java.util.List.of(DEPTH, PATH);

    /**
     * The member a caller has to send.
     *
     * <p>The depth is not one of them: the client's own schema makes it optional, and a caller who
     * names a path alone is asking for that node. Requiring it would refuse a request the client is
     * entitled to send, and defaulting to anything deeper would walk content nobody asked for.</p>
     */
    public static final java.util.List<String> REQUIRED = java.util.List.of(PATH);

    /** How deep an omitted depth reaches, which is the addressed node by itself. */
    public static final long THE_NODE_ALONE = 0;

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The address is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The depth is not a whole number of levels. */
        DEPTH_NOT_WHOLE,
        /** The depth is deeper than this deployment will walk. */
        DEPTH_ABOVE_MAXIMUM
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(LoadContentCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param maximumDepth how deep this deployment will walk, which the contract declares
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, long maximumDepth) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object with two members");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN, unknown.get() + " is not a member of this"
                    + " command's argument");
        }
        final Optional<String> absent = REQUIRED.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    absent.get() + " is required, and this command chooses no address for a"
                            + " caller");
        }
        return read(mapping, maximumDepth);
    }

    private static Outcome read(DocumentValue.Mapping mapping, long maximumDepth) {
        if (!(mapping.member(PATH).orElseThrow()
                instanceof final DocumentValue.Text path)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, PATH + " is not text");
        }
        if (path.value().isEmpty() || path.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    PATH + " is an absolute path beginning at the root");
        }
        final Optional<DocumentValue> asked = mapping.member(DEPTH);
        if (asked.isEmpty()) {
            return new Held(new LoadContentCommand(path.value(), THE_NODE_ALONE));
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Whole depth)) {
            return new Refused(Refusal.DEPTH_NOT_WHOLE, DEPTH + " is not a whole number");
        }
        if (depth.value() < 0) {
            return new Refused(Refusal.DEPTH_NOT_WHOLE, DEPTH + " is counted from zero, where zero"
                    + " is the addressed node by itself");
        }
        if (depth.value() > maximumDepth) {
            return new Refused(Refusal.DEPTH_ABOVE_MAXIMUM, depth.value() + " is deeper than the "
                    + maximumDepth + " this deployment walks");
        }
        return new Held(new LoadContentCommand(path.value(), depth.value()));
    }
}
