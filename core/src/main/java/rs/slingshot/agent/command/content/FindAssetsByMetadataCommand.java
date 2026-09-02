// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.search.PropertyPredicate;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which assets one caller is looking for, said as things about an asset rather than as a query.
 *
 * <p>Tags, media formats, a size range, and whatever else the shared predicate language can ask.
 * All of them are optional and a search naming none is every asset under the root, which is a
 * question about a digital asset library that a caller is entitled to ask and that the asset index
 * answers from the node type alone.</p>
 *
 * <p>The size bounds are two members rather than a range object because either end alone is a real
 * question — "everything over ten megabytes" is the one an operator asks when storage has grown —
 * and a range that required both would make them say a number they do not mean.</p>
 *
 * @param rootPath the subtree to search, which bounds the search rather than describing it
 * @param tags the tags to look for, empty where the caller named none
 * @param tagMatchMode whether an asset carrying any named tag matches, or only one carrying all
 * @param mediaFormats the formats to look for, empty where the caller named none
 * @param minimumByteLength the smallest asset to answer with, or {@link #NO_BOUND}
 * @param maximumByteLength the largest, or {@link #NO_BOUND}
 * @param predicates what else the caller asks about each candidate
 * @param window which page of the matches is wanted
 */
public record FindAssetsByMetadataCommand(String rootPath, List<String> tags,
                                          MatchMode tagMatchMode, List<String> mediaFormats,
                                          long minimumByteLength, long maximumByteLength,
                                          List<PropertyPredicate> predicates,
                                          ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_assets_by_metadata";

    /** The member the subtree to search is carried in. */
    public static final String ROOT_PATH = "root_path";

    /** The member the tags to look for are carried in. */
    public static final String TAGS = "tags";

    /** The member saying whether any named tag is enough or all of them are needed. */
    public static final String TAG_MATCH_MODE = "tag_match_mode";

    /** The member the media formats to look for are carried in. */
    public static final String MEDIA_FORMATS = "media_formats";

    /** The member the smallest asset to answer with is carried in. */
    public static final String MINIMUM_BYTE_LENGTH = "minimum_byte_length";

    /** The member the largest is carried in. */
    public static final String MAXIMUM_BYTE_LENGTH = "maximum_byte_length";

    /** Every member this command's argument has, and there is no ninth. */
    public static final List<String> MEMBERS = List.of(MAXIMUM_BYTE_LENGTH, MEDIA_FORMATS,
            MINIMUM_BYTE_LENGTH, PropertyPredicate.ARGUMENT_MEMBER, ResultWindow.ARGUMENT_MEMBER,
            ROOT_PATH, TAGS, TAG_MATCH_MODE);

    /** The member a caller has to send, every other one being a narrowing they may leave out. */
    public static final List<String> REQUIRED = List.of(ROOT_PATH);

    /** Where a caller named no size bound at that end. */
    public static final long NO_BOUND = -1;

    /** Holds the three lists apart from whatever the caller still has a reference to. */
    public FindAssetsByMetadataCommand {
        tags = List.copyOf(tags);
        mediaFormats = List.copyOf(mediaFormats);
        predicates = List.copyOf(predicates);
    }

    /**
     * The tags this search looks for.
     *
     * @return the tags, which nothing may add to
     */
    @Override
    public List<String> tags() {
        return Collections.unmodifiableList(tags);
    }

    /**
     * The media formats this search looks for.
     *
     * @return the formats, which nothing may add to
     */
    @Override
    public List<String> mediaFormats() {
        return Collections.unmodifiableList(mediaFormats);
    }

    /**
     * What else this search asks about each candidate.
     *
     * @return the predicates, which nothing may add to
     */
    @Override
    public List<PropertyPredicate> predicates() {
        return Collections.unmodifiableList(predicates);
    }

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The root is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** A list of names is not a list of names. */
        NOT_A_LIST_OF_NAMES,
        /** The tag match mode is neither of the two there are. */
        UNKNOWN_MATCH_MODE,
        /** A size bound is not a whole number, or is negative. */
        SIZE_NOT_WHOLE,
        /** The smallest asked for is larger than the largest, so nothing can match. */
        SIZE_RANGE_EMPTY,
        /** A predicate is not one this language defines. */
        PREDICATE_REFUSED,
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
    public record Held(FindAssetsByMetadataCommand command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds the lists, the window and the token
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object with a subtree in it");
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
            return new Refused(Refusal.MEMBER_ABSENT,
                    absent.get() + " is required; this command chooses no subtree for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ROOT_PATH).orElseThrow() instanceof final DocumentValue.Text root)
                || root.value().isEmpty() || root.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ROOT_PATH + " is an absolute path beginning at the root");
        }
        final Optional<List<String>> tags = namesIn(mapping, TAGS);
        final Optional<List<String>> formats = namesIn(mapping, MEDIA_FORMATS);
        if (tags.isEmpty() || formats.isEmpty()) {
            return new Refused(Refusal.NOT_A_LIST_OF_NAMES,
                    TAGS + " and " + MEDIA_FORMATS + " are lists of names");
        }
        final Optional<MatchMode> mode = tagMode(mapping);
        if (mode.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_MATCH_MODE, TAG_MATCH_MODE + " is neither "
                    + MatchMode.ANY.spelling() + " nor " + MatchMode.ALL.spelling());
        }
        return sized(new Narrowings(root.value(), tags.orElseThrow(), mode.orElseThrow(),
                formats.orElseThrow()), mapping, contract);
    }

    private static Optional<List<String>> namesIn(DocumentValue.Mapping mapping, String member) {
        final Optional<DocumentValue> asked = mapping.member(member);
        if (asked.isEmpty()) {
            return Optional.of(List.of());
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence items)) {
            return Optional.empty();
        }
        final List<String> names = items.items().stream()
                .filter(item -> item instanceof DocumentValue.Text)
                .map(item -> ((DocumentValue.Text) item).value())
                .toList();
        return names.size() == items.items().size() ? Optional.of(names) : Optional.empty();
    }

    private static Optional<MatchMode> tagMode(DocumentValue.Mapping mapping) {
        final Optional<DocumentValue> asked = mapping.member(TAG_MATCH_MODE);
        if (asked.isEmpty()) {
            // A caller who named tags and no mode is asking about any of them. That is the commoner
            // question and the safer default: answering with the assets carrying all of them would
            // silently answer a narrower question than the one that was asked.
            return Optional.of(MatchMode.ANY);
        }
        return asked.orElseThrow() instanceof final DocumentValue.Text spelled
                ? MatchMode.named(spelled.value()) : Optional.empty();
    }

    /**
     * What has been read so far, carried as one value rather than as a growing parameter list.
     *
     * <p>Reading this argument is a sequence of narrowings, and threading each one through the next
     * step as its own parameter is how a reader ends up taking eight. What travels between the
     * steps is the same thing every time: the search built so far.</p>
     *
     * @param rootPath the subtree to search
     * @param tags the tags to look for
     * @param tagMatchMode whether any named tag is enough or all of them are needed
     * @param mediaFormats the formats to look for
     */
    private record Narrowings(String rootPath, List<String> tags, MatchMode tagMatchMode,
                              List<String> mediaFormats) {
    }

    private static Outcome sized(Narrowings held, DocumentValue.Mapping mapping,
                                 AgentContract contract) {
        final Optional<Long> smallest = sizeIn(mapping, MINIMUM_BYTE_LENGTH);
        final Optional<Long> largest = sizeIn(mapping, MAXIMUM_BYTE_LENGTH);
        if (smallest.isEmpty() || largest.isEmpty()) {
            return new Refused(Refusal.SIZE_NOT_WHOLE,
                    "a size bound is a whole number of bytes, counted from zero");
        }
        if (smallest.orElseThrow() != NO_BOUND && largest.orElseThrow() != NO_BOUND
                && smallest.orElseThrow() > largest.orElseThrow()) {
            return new Refused(Refusal.SIZE_RANGE_EMPTY, "the smallest asset asked for is larger"
                    + " than the largest, so nothing can match and an empty answer would read as"
                    + " a library with nothing in it");
        }
        return predicated(held, smallest.orElseThrow(), largest.orElseThrow(), mapping, contract);
    }

    private static Optional<Long> sizeIn(DocumentValue.Mapping mapping, String member) {
        final Optional<DocumentValue> asked = mapping.member(member);
        if (asked.isEmpty()) {
            return Optional.of(NO_BOUND);
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Whole size)
                || size.value() < 0) {
            return Optional.empty();
        }
        return Optional.of(size.value());
    }

    private static Outcome predicated(Narrowings held, long smallest, long largest,
                                      DocumentValue.Mapping mapping, AgentContract contract) {
        final PropertyPredicate.Outcome predicates = PropertyPredicate.listOf(mapping, contract);
        if (predicates instanceof final PropertyPredicate.Refused refused) {
            return new Refused(Refusal.PREDICATE_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        if (window instanceof final ResultWindow.Refused refused) {
            return new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString());
        }
        return bounded(new FindAssetsByMetadataCommand(held.rootPath(), held.tags(),
                held.tagMatchMode(), held.mediaFormats(), smallest, largest,
                ((PropertyPredicate.Held) predicates).predicates(),
                ((ResultWindow.Held) window).window()), contract);
    }

    private static Outcome bounded(FindAssetsByMetadataCommand command, AgentContract contract) {
        final long tagBound = contract.value(ContractLimit.MAXIMUM_REQUESTED_ASSET_TAGS);
        if (command.tags().size() > tagBound) {
            return new Refused(Refusal.NOT_A_LIST_OF_NAMES, command.tags().size() + " tags is more"
                    + " than the " + tagBound + " this deployment looks for at once");
        }
        final long formatBound = contract.value(ContractLimit.MAXIMUM_REQUESTED_MEDIA_FORMATS);
        if (command.mediaFormats().size() > formatBound) {
            return new Refused(Refusal.NOT_A_LIST_OF_NAMES, command.mediaFormats().size()
                    + " formats is more than the " + formatBound + " this deployment looks for at"
                    + " once");
        }
        return new Held(command);
    }
}
