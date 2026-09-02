// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A content fragment to make: where it goes, what it is called, and which model declares it.
 *
 * <p>The model is required and never inferred, for the same reason a page's template is. A fragment
 * made without a model is a node holding text that no authoring tool will open and no component
 * will render — it exists and it is addressable, which is worse than a refusal, because a refusal
 * is noticed the same minute.</p>
 *
 * <p>The title may be left out, and where it is the fragment is called by its node name. That is
 * the client's own shape rather than this side's preference: the published argument requires the
 * parent, the name and the model, and nothing else.</p>
 *
 * @param parentPath where the fragment goes
 * @param name what the fragment's own node is called, which is what appears in its address
 * @param title what the fragment is called to a person, which may be {@link #NO_TITLE}
 * @param modelPath the model that declares which elements this fragment has
 * @param elements what to set those elements to on the master variation
 */
public record CreateContentFragmentCommand(String parentPath, String name, String title,
                                           String modelPath, FragmentElements elements) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "create_content_fragment";

    /** The member the parent's address is carried in. */
    public static final String PARENT_PATH = "parent_path";

    /** The member the fragment's own node name is carried in. */
    public static final String NAME = "name";

    /** The member the fragment's title is carried in. */
    public static final String TITLE = "title";

    /** What the title says when the caller named none, so the fragment is known by its own name. */
    public static final String NO_TITLE = FragmentPaths.NO_TITLE;

    /** The member the model's address is carried in. */
    public static final String MODEL_PATH = "model_path";

    /** Every member this command's argument has, and there is no sixth. */
    public static final List<String> MEMBERS = List.of(FragmentElements.ARGUMENT_MEMBER, MODEL_PATH,
            NAME, PARENT_PATH, TITLE);

    /** The members a caller has to send; the title and the elements may be left out. */
    public static final List<String> REQUIRED = List.of(MODEL_PATH, NAME, PARENT_PATH);

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
        /** The title is longer than the contract allows. */
        TITLE_TOO_LONG,
        /** The elements are not ones this contract writes. */
        ELEMENTS_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(CreateContentFragmentCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the element rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Where this fragment will be, which is the parent and the name joined.
     *
     * @return the address the fragment will have
     */
    public String targetPath() {
        return parentPath + "/" + name;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every address, name and element
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object saying where a"
                    + " fragment goes and which model declares it");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; this command"
                    + " chooses neither where a fragment goes, what it is called, nor which model"
                    + " declares it");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> parent = FragmentPaths.absolute(mapping, PARENT_PATH, contract);
        final Optional<String> model = FragmentPaths.absolute(mapping, MODEL_PATH, contract);
        if (parent.isEmpty() || model.isEmpty()) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, PARENT_PATH + " and " + MODEL_PATH
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
        final FragmentPaths.TitleOutcome title = FragmentPaths.title(mapping, TITLE, contract);
        return title instanceof final FragmentPaths.TitleRefused refused
                ? new Refused(Refusal.TITLE_TOO_LONG, refused.detail())
                : elemented(parent.orElseThrow(), name.orElseThrow(), model.orElseThrow(),
                        ((FragmentPaths.TitleHeld) title).title(), mapping, contract);
    }

    private static Outcome elemented(String parent, String name, String model, String title,
                                     DocumentValue.Mapping mapping, AgentContract contract) {
        final FragmentElements.Outcome elements = FragmentElements.of(mapping, contract);
        return elements instanceof final FragmentElements.Refused refused
                ? new Refused(Refusal.ELEMENTS_REJECTED,
                        refused.refusal() + ": " + refused.detail())
                : new Held(new CreateContentFragmentCommand(parent, name, title, model,
                        ((FragmentElements.Held) elements).elements()));
    }
}
