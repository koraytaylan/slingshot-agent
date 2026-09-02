// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A folder to make inside a digital asset library: where it goes and what it is called.
 *
 * <p>The title is optional and is what a person sees; the name is the node's own and is what
 * appears in every address underneath it. Keeping them apart is why a folder can be renamed for
 * people without every asset inside it changing address.</p>
 *
 * @param parentPath where the folder goes
 * @param name the folder's own node name
 * @param title what it is called to a person, empty where the caller named none
 */
public record CreateAssetFolderCommand(String parentPath, String name, String title) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "create_asset_folder";

    /** The member the parent's address is carried in. */
    public static final String PARENT_PATH = "parent_path";

    /** The member the folder's own node name is carried in. */
    public static final String NAME = "name";

    /** The member the folder's title is carried in. */
    public static final String TITLE = "title";

    /** Where a caller named no title, and the folder is known by its own name. */
    public static final String NO_TITLE = "";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS = List.of(NAME, PARENT_PATH, TITLE);

    /** The members a caller has to send; only the title may be left out. */
    public static final List<String> REQUIRED = List.of(NAME, PARENT_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The parent is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The folder's own name is empty, too long, or carries a path. */
        NAME_REJECTED,
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
    public record Held(CreateAssetFolderCommand command) implements Outcome {
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
     * Where this folder will be.
     *
     * @return the address the folder will have
     */
    public String targetPath() {
        return parentPath + "/" + name;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address, the name and the title
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying where a folder goes and what it is called");
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
                    + " chooses neither where a folder goes nor what it is called");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(PARENT_PATH).orElseThrow() instanceof final DocumentValue.Text parent)
                || parent.value().isEmpty() || parent.value().charAt(0) != '/'
                || parent.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    PARENT_PATH + " is an absolute path beginning at the root");
        }
        if (!(mapping.member(NAME).orElseThrow() instanceof final DocumentValue.Text name)
                || name.value().isBlank() || name.value().indexOf('/') >= 0
                || name.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_NAME_BYTES)) {
            return new Refused(Refusal.NAME_REJECTED, NAME + " is one node's own name: not empty,"
                    + " not a path, and within the "
                    + contract.value(ContractLimit.MAXIMUM_REPOSITORY_NAME_BYTES)
                    + " a name may be");
        }
        return titled(parent.value(), name.value(), mapping, contract);
    }

    private static Outcome titled(String parent, String name, DocumentValue.Mapping mapping,
                                  AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(TITLE);
        final long bound = contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES);
        if (asked.isPresent() && (!(asked.orElseThrow() instanceof final DocumentValue.Text title)
                || title.value().length() > bound)) {
            return new Refused(Refusal.TITLE_TOO_LONG,
                    TITLE + " is text within the " + bound + " a title may be");
        }
        return new Held(new CreateAssetFolderCommand(parent, name, asked
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(NO_TITLE)));
    }
}
