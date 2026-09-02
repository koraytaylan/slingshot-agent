// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What a command did instead of producing an answer, and whether it had an effect.
 *
 * <p>Every category states its effect, and one of the three values it may state is that nobody
 * knows. That case is first-class here because it is the one a caller most needs to distinguish and
 * the one most often flattened: told "it failed", a caller retries; told "it failed and may have
 * changed something", a caller looks before it retries. Reporting the second as the first is how a
 * page gets created twice.</p>
 *
 * @param category which way this command failed, which is also what says whether it
 *     had an effect
 */
public record CommandFailure(Category category) {

    /** The member the category is carried in. */
    public static final String CATEGORY = "failure_category";

    /** The member an effect is reported in, which is the category's own answer. */
    public static final String EFFECT = "effect";

    /** Every member a failure document has, and there is no third. */
    public static final List<String> MEMBERS = List.of(EFFECT, CATEGORY);

    /** Whether the command changed anything before it stopped. */
    public enum Effect {
        /** It changed nothing, and a caller may retry without looking first. */
        NONE("none"),
        /** It changed something, and a retry would be a second change. */
        APPLIED("applied"),
        /** Nobody knows, which is a caller's cue to look before it decides. */
        UNKNOWN("unknown");

        private final String spelling;

        Effect(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this effect is spelled on the wire.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }
    }

    /** Every way a command can fail that this build knows, each stating its own effect. */
    public enum Category {

        /** The submission was refused before anything ran. */
        REFUSED_BEFORE_STARTING("refused_before_starting", Effect.NONE),

        /** An argument was not one this command takes. */
        ARGUMENT_REJECTED("argument_rejected", Effect.NONE),

        /** The caller may not do this, and the repository said so before anything changed. */
        PERMISSION_DENIED("permission_denied", Effect.NONE),

        /** What the command was asked about is not there. */
        NOT_FOUND("not_found", Effect.NONE),

        /** Something else changed underneath it and the command refused rather than overwrite. */
        CONFLICT("conflict", Effect.NONE),

        /** The command reached its execution budget part-way through. */
        BUDGET_SPENT("budget_spent", Effect.UNKNOWN),

        /** The platform failed under it, and what it had done is not known from here. */
        PLATFORM_FAILED("platform_failed", Effect.UNKNOWN),

        /** The worker holding it stopped, and whether it committed first is not known from here. */
        INTERRUPTED("interrupted", Effect.UNKNOWN),

        /** It changed something and then failed, which is a different thing from failing. */
        APPLIED_THEN_FAILED("applied_then_failed", Effect.APPLIED);

        private final String spelling;
        private final Effect effect;

        Category(String spelling, Effect effect) {
            this.spelling = spelling;
            this.effect = effect;
        }

        /**
         * How this category is spelled on the wire.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * Whether a command in this category changed anything.
         *
         * @return the effect this category states
         */
        public Effect effect() {
            return effect;
        }

        /**
         * The category one spelling names.
         *
         * @param spelling the spelling
         * @return the category, or nothing where this build knows no such category
         */
        public static Optional<Category> named(String spelling) {
            return Arrays.stream(values())
                    .filter(category -> category.spelling.equals(spelling))
                    .findFirst();
        }

        /**
         * Every spelling this build knows.
         *
         * @return the spellings, sorted
         */
        public static List<String> spellings() {
            return Arrays.stream(values())
                    .map(Category::spelling)
                    .sorted()
                    .toList();
        }
    }

    /** Why a document is not a failure this build knows. */
    public enum Refusal {
        /** The document is not an object. */
        NOT_A_DOCUMENT,
        /** The category is missing, and a failure without one says nothing a caller can act on. */
        CATEGORY_ABSENT,
        /** The category is not one this build knows. */
        UNKNOWN_CATEGORY,
        /** The document also carries an answer, and a command produced one or did not. */
        CARRIES_A_RESULT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The effect stated is not the one the category states. */
        EFFECT_DISAGREES
    }

    /** The result of reading one: the failure, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that is a failure this build knows.
     *
     * @param failure the failure it carried
     */
    public record Held(CommandFailure failure) implements Outcome {
    }

    /**
     * A document that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads a failure document.
     *
     * @param document the document
     * @return the failure, or the one reason there is none
     */
    public static Outcome read(DocumentValue document) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "a failure is an object");
        }
        final Optional<String> carried = mapping.members().keySet().stream()
                .filter(ResultDelivery.MEMBERS::contains)
                .findFirst();
        if (carried.isPresent()) {
            return new Refused(Refusal.CARRIES_A_RESULT, carried.get()
                    + " belongs to an answer, and a command produced an answer or did not");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member a failure document has");
        }
        return categorised(mapping);
    }

    private static Outcome categorised(DocumentValue.Mapping mapping) {
        final Optional<String> spelling = mapping.member(CATEGORY)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
        if (spelling.isEmpty()) {
            return new Refused(Refusal.CATEGORY_ABSENT,
                    "a failure without a category says nothing a caller can act on");
        }
        final Optional<Category> category = Category.named(spelling.get());
        if (category.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_CATEGORY,
                    "this build knows no category spelled that way");
        }
        return stated(mapping, category.get());
    }

    private static Outcome stated(DocumentValue.Mapping mapping, Category category) {
        final Optional<String> effect = mapping.member(EFFECT)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
        if (effect.isPresent() && !effect.get().equals(category.effect().spelling())) {
            return new Refused(Refusal.EFFECT_DISAGREES, category.spelling() + " states "
                    + category.effect().spelling() + " and the document says " + effect.get());
        }
        return new Held(new CommandFailure(category));
    }

    /**
     * Whether this command changed anything before it stopped.
     *
     * @return the effect its category states
     */
    public Effect effect() {
        return category.effect();
    }
}
