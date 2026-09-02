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
 * Which asset's metadata to change, and what to change about it.
 *
 * <p>The same two lists a page update carries, applied to the asset's metadata node rather than to
 * the asset itself. A property in neither is left as it was — and for an asset that matters more
 * than for a page, because metadata is written by several things at once and a caller who sent a
 * partial view would silently drop whatever the last workflow put there.</p>
 *
 * @param assetPath the asset to change
 * @param change what to write and what to take away
 */
public record UpdateAssetMetadataCommand(String assetPath, PropertyChange change) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "update_asset_metadata";

    /** The member the asset's address is carried in. */
    public static final String ASSET_PATH = "asset_path";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS = List.of(ASSET_PATH, PropertyChange.PROPERTIES,
            PropertyValue.CARDINALITY, PropertyChange.REMOVED_PROPERTY_NAMES);

    /** The member a caller has to send; every change this command makes is optional. */
    public static final List<String> REQUIRED = List.of(ASSET_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The asset is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The change is not one this contract makes. */
        CHANGE_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(UpdateAssetMetadataCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the property rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address and both lists
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming an asset and what to change about it");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(ASSET_PATH).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    ASSET_PATH + " is required; this command chooses no asset for a caller");
        }
        if (!(mapping.member(ASSET_PATH).orElseThrow() instanceof final DocumentValue.Text asset)
                || asset.value().isEmpty() || asset.value().charAt(0) != '/'
                || asset.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ASSET_PATH + " is an absolute path beginning at the root");
        }
        final PropertyChange.Outcome change = PropertyChange.of(mapping, contract);
        return change instanceof final PropertyChange.Refused refused
                ? new Refused(Refusal.CHANGE_REJECTED, refused.refusal() + ": " + refused.detail())
                : new Held(new UpdateAssetMetadataCommand(asset.value(),
                        ((PropertyChange.Held) change).change()));
    }
}
