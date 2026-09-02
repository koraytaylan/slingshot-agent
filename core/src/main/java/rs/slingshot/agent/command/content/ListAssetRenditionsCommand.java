// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One asset whose renditions are wanted, and which window of them.
 *
 * <p>Renditions are where an asset's storage actually goes: an original of two megabytes can carry
 * a dozen derived copies that together are larger than it. An operator asking why a repository has
 * grown is asking this question, which is why the answer is about sizes and never about bytes.</p>
 *
 * @param assetPath the asset whose renditions are wanted
 * @param window which page of those renditions is wanted
 */
public record ListAssetRenditionsCommand(String assetPath, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_asset_renditions";

    /** The member the asset's address is carried in. */
    public static final String ASSET_PATH = "asset_path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS = List.of(ASSET_PATH,
            ResultWindow.ARGUMENT_MEMBER);

    /** The member a caller has to send, the window being the one with a default. */
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
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(ListAssetRenditionsCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the window
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object with two members");
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
                    + " chooses neither an asset nor a window of its renditions for a caller");
        }
        if (!(mapping.member(ASSET_PATH).orElseThrow() instanceof final DocumentValue.Text asset)
                || asset.value().isEmpty() || asset.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ASSET_PATH + " is an absolute path beginning at the root");
        }
        final ResultWindow.Outcome window =
                ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new ListAssetRenditionsCommand(asset.value(),
                        ((ResultWindow.Held) window).window()));
    }
}
