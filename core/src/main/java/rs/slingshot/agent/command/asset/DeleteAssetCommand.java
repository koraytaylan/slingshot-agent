// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.ReferencePolicy;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which asset to remove, and what to do about the things pointing at it.
 *
 * <p>The policy is required and has no default, for the same reason it is on a page's delete and
 * with more force: an asset is referenced from places its owner has never seen, and deleting one
 * that something still uses leaves a broken image on a page nobody was looking at.</p>
 *
 * @param assetPath the asset to remove
 * @param referencePolicy what to do about the things pointing at it
 */
public record DeleteAssetCommand(String assetPath, ReferencePolicy referencePolicy) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "delete_asset";

    /** The member the asset's address is carried in. */
    public static final String ASSET_PATH = "asset_path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(ASSET_PATH, ReferencePolicy.ARGUMENT_MEMBER);

    /** The members a caller has to send, which is both: neither has a defensible default. */
    public static final List<String> REQUIRED = MEMBERS;

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
        /** The reference policy is not one of the two there are. */
        UNKNOWN_REFERENCE_POLICY
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(DeleteAssetCommand command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds the address
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming an asset and what to do about its references");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; an asset is"
                    + " referenced from places its owner has never seen, so what to do about those"
                    + " is not something this side may choose");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ASSET_PATH).orElseThrow() instanceof final DocumentValue.Text asset)
                || asset.value().isEmpty() || asset.value().charAt(0) != '/'
                || asset.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ASSET_PATH + " is an absolute path beginning at the root");
        }
        if (!(mapping.member(ReferencePolicy.ARGUMENT_MEMBER).orElseThrow()
                instanceof final DocumentValue.Text spelled)) {
            return new Refused(Refusal.UNKNOWN_REFERENCE_POLICY,
                    ReferencePolicy.ARGUMENT_MEMBER + " is one of " + ReferencePolicy.spellings());
        }
        return ReferencePolicy.named(spelled.value())
                .<Outcome>map(policy -> new Held(new DeleteAssetCommand(asset.value(), policy)))
                .orElseGet(() -> new Refused(Refusal.UNKNOWN_REFERENCE_POLICY, spelled.value()
                        + " is not a policy this contract has; they are "
                        + ReferencePolicy.spellings()));
    }
}
