// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * An asset to make: where it goes, what it is called, what is in it, and what is known about it.
 *
 * <p>The only mutation carrying content rather than a description of it. The payload is checked
 * whole before anything is written — its declared type against a closed set, its encoded size and
 * its decoded size against the contract — because an asset half written is a digital asset library
 * with something in it nobody can open.</p>
 *
 * @param parentPath the folder it goes in
 * @param name the asset's own node name, which is also its file name
 * @param payload what is in it
 * @param metadata what is known about it
 */
public record CreateAssetCommand(String parentPath, String name, AssetPayload payload,
                                 PropertyChange metadata) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "create_asset";

    /** The member the folder's address is carried in. */
    public static final String PARENT_PATH = "parent_path";

    /** The member the asset's own node name is carried in. */
    public static final String NAME = "name";

    /** The member what is known about the asset is carried in. */
    public static final String METADATA = "metadata";

    /** Every member this command's argument has, and there is no seventh. */
    public static final List<String> MEMBERS = List.of(AssetPayload.ARGUMENT_MEMBER,
            AssetPayload.ENCODED_CONTENT, METADATA, NAME, PARENT_PATH, PropertyValue.CARDINALITY);

    /** The members a caller has to send; only the metadata may be left out. */
    public static final List<String> REQUIRED =
            List.of(AssetPayload.ARGUMENT_MEMBER, NAME, PARENT_PATH);

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
        /** The asset's own name is empty, too long, or carries a path. */
        NAME_REJECTED,
        /** The payload is not one this build stores. */
        PAYLOAD_REJECTED,
        /** The payload is larger than the contract allows. */
        PAYLOAD_TOO_LARGE,
        /** The payload's declared media type is not one this build stores. */
        MEDIA_TYPE_UNSUPPORTED,
        /** The metadata is not something this contract writes. */
        METADATA_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(CreateAssetCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which never quotes the content
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Where this asset will be.
     *
     * @return the address the asset will have
     */
    public String targetPath() {
        return parentPath + "/" + name;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address, the name and the bytes
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying where an asset goes and what is in it");
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
                    + " chooses neither where an asset goes, what it is called, nor what is in it");
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
        return loaded(parent.value(), name.value(), mapping, contract);
    }

    private static Outcome loaded(String parent, String name, DocumentValue.Mapping mapping,
                                  AgentContract contract) {
        final AssetPayload.Outcome payload =
                AssetPayload.of(mapping.member(AssetPayload.ARGUMENT_MEMBER).orElseThrow(),
                        contract);
        if (payload instanceof final AssetPayload.Refused refused) {
            return new Refused(refusalFor(refused.refusal()), refused.detail());
        }
        final PropertyChange.Outcome metadata = PropertyChange.of(metadataOf(mapping), contract);
        if (metadata instanceof final PropertyChange.Refused refused) {
            return new Refused(Refusal.METADATA_REJECTED,
                    refused.refusal() + ": " + refused.detail());
        }
        return new Held(new CreateAssetCommand(parent, name,
                ((AssetPayload.Held) payload).payload(),
                ((PropertyChange.Held) metadata).change()));
    }

    /**
     * Which of this command's refusals one payload refusal is.
     *
     * <p>Kept distinct because the caller's next move differs: a payload this build will not store
     * is a different problem from one that is simply too big, and the second has a smaller version
     * that would work.</p>
     *
     * @param refusal why the payload was refused
     * @return this command's own refusal for it
     */
    private static Refusal refusalFor(AssetPayload.Refusal refusal) {
        return switch (refusal) {
            case MEDIA_TYPE_UNSUPPORTED -> Refusal.MEDIA_TYPE_UNSUPPORTED;
            case ENCODED_TOO_LARGE, DECODED_TOO_LARGE -> Refusal.PAYLOAD_TOO_LARGE;
            case NOT_A_DOCUMENT, MEMBERS_WRONG, NOT_ENCODED -> Refusal.PAYLOAD_REJECTED;
        };
    }

    /**
     * The metadata as a change document, which is what reads it.
     *
     * <p>Wrapped rather than read separately: what a caller may write about a new asset is exactly
     * what they may write about an existing one, and one reader for both keeps those the same.</p>
     *
     * @param mapping the argument document
     * @return a document the change reader understands
     */
    private static DocumentValue.Mapping metadataOf(DocumentValue.Mapping mapping) {
        final java.util.SequencedMap<String, DocumentValue> written = new java.util.LinkedHashMap<>();
        mapping.member(METADATA)
                .ifPresent(metadata -> written.put(PropertyChange.PROPERTIES, metadata));
        return new DocumentValue.Mapping(written);
    }
}
