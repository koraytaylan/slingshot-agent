// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The three members every fragment argument is made of: an address, a node name and a title.
 *
 * <p>Read here once rather than in each of the six. Six copies of the same three checks is six
 * chances for one of them to drift a bound, and the drift that matters is the quiet direction —
 * a reader that refuses at a smaller number than the published schema declares refuses something
 * the caller was told they could send.</p>
 */
public final class FragmentPaths {

    private FragmentPaths() {
    }

    /** What a title member says when the caller named none. */
    public static final String NO_TITLE = "";

    /** What reading a title produced: the title, or the one reason there is none. */
    public sealed interface TitleOutcome permits TitleHeld, TitleRefused {
    }

    /**
     * A title this contract writes, which may be no title at all.
     *
     * @param title what the fragment is called, or {@link #NO_TITLE} where the caller named
     *     nothing
     */
    public record TitleHeld(String title) implements TitleOutcome {
    }

    /**
     * One it does not.
     *
     * @param detail what was seen, said as the caller would fix it
     */
    public record TitleRefused(String detail) implements TitleOutcome {
    }

    /**
     * One member read as an absolute repository path.
     *
     * @param mapping the argument document
     * @param member which member to read
     * @param contract the authenticated contract, which bounds how long a path may be
     * @return the path, or nothing where that member is not one
     */
    public static Optional<String> absolute(DocumentValue.Mapping mapping, String member,
                                            AgentContract contract) {
        final Optional<DocumentValue> held = mapping.member(member);
        if (held.isEmpty() || !(held.orElseThrow() instanceof final DocumentValue.Text text)
                || text.value().isEmpty() || text.value().charAt(0) != '/'
                || text.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return Optional.empty();
        }
        return Optional.of(text.value());
    }

    /**
     * One member read as a single node's own name.
     *
     * @param mapping the argument document
     * @param member which member to read
     * @param contract the authenticated contract, which bounds how long a name may be
     * @return the name, or nothing where that member is not one
     */
    public static Optional<String> nodeName(DocumentValue.Mapping mapping, String member,
                                            AgentContract contract) {
        final Optional<DocumentValue> held = mapping.member(member);
        if (held.isEmpty() || !(held.orElseThrow() instanceof final DocumentValue.Text text)
                || text.value().isBlank() || text.value().indexOf('/') >= 0
                || text.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_NAME_BYTES)) {
            return Optional.empty();
        }
        return Optional.of(text.value());
    }

    /**
     * One member read as a title, which every fragment argument may leave out.
     *
     * @param mapping the argument document
     * @param member which member to read
     * @param contract the authenticated contract, which bounds how long a title may be
     * @return the title, or the one reason there is none
     */
    public static TitleOutcome title(DocumentValue.Mapping mapping, String member,
                                     AgentContract contract) {
        final Optional<DocumentValue> held = mapping.member(member);
        if (held.isEmpty()) {
            return new TitleHeld(NO_TITLE);
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES);
        if (!(held.orElseThrow() instanceof final DocumentValue.Text text)) {
            return new TitleRefused(member + " is what a fragment is called to a person, which is"
                    + " text");
        }
        return text.value().length() > bound
                ? new TitleRefused(member + " is longer than the " + bound + " a title may be")
                : new TitleHeld(text.value());
    }
}
