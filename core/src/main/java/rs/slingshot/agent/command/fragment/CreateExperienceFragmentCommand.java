// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * An experience fragment to make: where it goes, what it is called, and its first variation.
 *
 * <p>The variation's name is required, unlike a content fragment's, and the difference is not an
 * inconsistency. A content fragment has a master variation whether anybody names it or not; an
 * experience fragment has no variations until one is made, and a fragment with none is a container
 * that renders nothing. There is no name this side could choose that would not be a guess about
 * what the second variation will be called.</p>
 *
 * @param parentPath where the fragment goes
 * @param name what the fragment's own node is called
 * @param title what the fragment is called to a person, which may be {@link #NO_TITLE}
 * @param templatePath what the variation is made from
 * @param variationName what the first variation's own node is called
 */
public record CreateExperienceFragmentCommand(String parentPath, String name, String title,
                                              String templatePath, String variationName) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "create_experience_fragment";

    /** The member the parent's address is carried in. */
    public static final String PARENT_PATH = "parent_path";

    /** The member the fragment's own node name is carried in. */
    public static final String NAME = "name";

    /** The member the fragment's title is carried in. */
    public static final String TITLE = "title";

    /** What the title says when the caller named none, so the fragment is known by its own name. */
    public static final String NO_TITLE = FragmentPaths.NO_TITLE;

    /** The member the template's address is carried in. */
    public static final String TEMPLATE_PATH = "template_path";

    /** The member the first variation's name is carried in. */
    public static final String VARIATION_NAME = "variation_name";

    /** Every member this command's argument has, and there is no sixth. */
    public static final List<String> MEMBERS =
            List.of(NAME, PARENT_PATH, TEMPLATE_PATH, TITLE, VARIATION_NAME);

    /** The members a caller has to send; only the title may be left out. */
    public static final List<String> REQUIRED =
            List.of(NAME, PARENT_PATH, TEMPLATE_PATH, VARIATION_NAME);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** An address is not an absolute repository path, or is longer than the contract allows. */
        NOT_AN_ABSOLUTE_PATH,
        /** The fragment's own name is empty, too long, or made of something a node name is not. */
        NAME_REJECTED,
        /** The variation's name is empty, too long, or made of something a name is not. */
        VARIATION_NAME_REJECTED,
        /** The title is longer than the contract allows. */
        TITLE_TOO_LONG
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(CreateExperienceFragmentCommand command) implements Outcome {
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
     * Where this fragment will be.
     *
     * @return the address the fragment will have
     */
    public String targetPath() {
        return parentPath + "/" + name;
    }

    /**
     * Where its first variation will be, which is inside the fragment.
     *
     * @return the address the variation will have
     */
    public String variationPath() {
        return targetPath() + "/" + variationName;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every address and name
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object saying where a"
                    + " fragment goes and what its first variation is made from");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final Optional<String> absent = REQUIRED.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; a fragment with"
                    + " no variation is a container that renders nothing, and there is no name for"
                    + " one this side could choose without guessing");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> parent = FragmentPaths.absolute(mapping, PARENT_PATH, contract);
        final Optional<String> template = FragmentPaths.absolute(mapping, TEMPLATE_PATH, contract);
        if (parent.isEmpty() || template.isEmpty()) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, PARENT_PATH + " and " + TEMPLATE_PATH
                    + " are absolute paths beginning at the root, within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)
                    + " a path may be");
        }
        final Optional<String> name = FragmentPaths.nodeName(mapping, NAME, contract);
        if (name.isEmpty()) {
            return new Refused(Refusal.NAME_REJECTED, NAME + " is one node's own name: not empty,"
                    + " not a path, and within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_NAME_BYTES)
                    + " a fragment's name may be");
        }
        final Optional<String> variation = variationOf(mapping, contract);
        if (variation.isEmpty()) {
            return new Refused(Refusal.VARIATION_NAME_REJECTED, VARIATION_NAME + " is one"
                    + " variation's own name: not empty, not a path, and within the "
                    + contract.value(ContractLimit.MAXIMUM_EXPERIENCE_FRAGMENT_VARIATION_NAME_BYTES)
                    + " a variation's name may be");
        }
        return titled(parent.orElseThrow(), name.orElseThrow(), template.orElseThrow(),
                variation.orElseThrow(), mapping, contract);
    }

    private static Optional<String> variationOf(DocumentValue.Mapping mapping,
                                                AgentContract contract) {
        final Optional<DocumentValue> held = mapping.member(VARIATION_NAME);
        if (held.isEmpty() || !(held.orElseThrow() instanceof final DocumentValue.Text text)
                || text.value().isBlank() || text.value().indexOf('/') >= 0
                || text.value().length() > contract.value(
                        ContractLimit.MAXIMUM_EXPERIENCE_FRAGMENT_VARIATION_NAME_BYTES)) {
            return Optional.empty();
        }
        return Optional.of(text.value());
    }

    private static Outcome titled(String parent, String name, String template, String variation,
                                  DocumentValue.Mapping mapping, AgentContract contract) {
        final FragmentPaths.TitleOutcome title = FragmentPaths.title(mapping, TITLE, contract);
        return title instanceof final FragmentPaths.TitleRefused refused
                ? new Refused(Refusal.TITLE_TOO_LONG, refused.detail())
                : new Held(new CreateExperienceFragmentCommand(parent, name,
                        ((FragmentPaths.TitleHeld) title).title(), template, variation));
    }
}
