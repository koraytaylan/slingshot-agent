// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Whether a configuration property's value may be reported, and what to answer when it may not.
 *
 * <p>Two questions, kept apart on purpose. <em>Which properties this configuration has</em> is one
 * question and it is almost always safe to answer: an operator debugging a deployment needs to know
 * a property exists before they can ask about it. <em>What one holds</em> is a different question,
 * and for a password, a token, or a private key the answer is that this agent does not say.</p>
 *
 * <p>Which properties are secret is the platform's own answer rather than this build's. The Meta
 * Type Service says whether a property is a password, and reading that is how this stays correct on
 * a configuration nobody here has ever seen. A property the service says nothing about is
 * redacted — "nobody told us" and "it is safe" are not the same sentence, and being wrong about
 * that runs in one direction only.</p>
 *
 * <p>Redacted is not masked. A masked value is still a value: it has a length, it changes when the
 * secret changes, and somebody comparing two answers learns something from that. A redacted
 * property carries no value member at all — the operator learns the property exists and what the
 * service said about it, and nothing else.</p>
 */
public final class ValueDisclosure {

    private ValueDisclosure() {
    }

    /** The member the Meta Type Service's own answer is carried in. */
    public static final String METATYPE_EVIDENCE = "metatype_evidence";

    /** The member what was observed is carried in. */
    public static final String OBSERVATION = "observation";

    /** The member saying whether the observation carries a value. */
    public static final String VISIBILITY = "visibility";

    /** The member a visible value is carried in. */
    public static final String VALUE = "value";

    /** Every member one property's answer has, and there is no third. */
    public static final List<String> MEMBERS = List.of(METATYPE_EVIDENCE, OBSERVATION);

    /** How the evidence member spells a property the platform calls a secret. */
    public static final String PASSWORD_EVIDENCE = "password";

    /** How an observation spells a value this agent read. */
    public static final String VISIBLE = "visible";

    /** How an observation spells a value this agent did not read. */
    public static final String REDACTED = "redacted";

    /** What the Meta Type Service said about one property. */
    public enum Evidence {
        /** It is a password, so the value is not read. */
        PASSWORD(PASSWORD_EVIDENCE),
        /** It is described and is not a password, which is the only case a value is read in. */
        NON_PASSWORD("non_password"),
        /** The service said nothing, which is not the same as saying it is safe. */
        UNAVAILABLE("unavailable");

        private final String spelling;

        Evidence(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How the wire spells this evidence.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * Whether this evidence is enough to report a value.
         *
         * <p>Exactly one of the three is. Absence of evidence is not evidence of safety, and a
         * property nothing describes is the most likely one to be a credential somebody added by
         * hand.</p>
         *
         * @return whether it is
         */
        public boolean permitsReading() {
            return this == NON_PASSWORD;
        }

        /**
         * The evidence one spelling names.
         *
         * @param spelled what was written
         * @return the evidence, or nothing where nothing is spelled that way
         */
        public static Optional<Evidence> named(String spelled) {
            return Arrays.stream(values())
                    .filter(evidence -> evidence.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Every evidence, spelled as the wire spells it.
         *
         * @return the spellings, in declaration order
         */
        public static List<String> spellings() {
            return Arrays.stream(values()).map(Evidence::spelling).toList();
        }
    }

    /** What one property's answer is: its value, or the fact that there is none. */
    public sealed interface Observation permits Visible, Redacted {
    }

    /**
     * A property this agent read.
     *
     * @param value what it holds
     */
    public record Visible(ConfigurationValue value) implements Observation {
    }

    /** One it did not. */
    public record Redacted() implements Observation {
    }

    /**
     * What to answer about one property.
     *
     * <p>The value is a parameter rather than something fetched here, and it is only ever used on
     * the one branch that may use it. A caller that reads a password from the platform and then
     * asks this whether it may say so has already read it; keeping the decision ahead of the read
     * is that caller's job, and this is what tells them the answer.</p>
     *
     * @param evidence what the Meta Type Service said about it
     * @param value what it holds, used only where the evidence permits reading
     * @return the observation
     */
    public static Observation of(Evidence evidence, ConfigurationValue value) {
        return evidence.permitsReading() ? new Visible(value) : new Redacted();
    }

    /**
     * One property as it appears in an answer.
     *
     * <p>A redacted property carries no value member at all rather than an empty one, a null one,
     * or a row of asterisks. Anything occupying the member's place is something a reader can
     * measure.</p>
     *
     * @param evidence what the service said
     * @param observation what was observed
     * @return the document
     */
    public static DocumentValue.Mapping documentOf(Evidence evidence, Observation observation) {
        final SequencedMap<String, DocumentValue> property = new LinkedHashMap<>();
        property.put(METATYPE_EVIDENCE, new DocumentValue.Text(evidence.spelling()));
        property.put(OBSERVATION, observed(observation));
        return new DocumentValue.Mapping(property);
    }

    private static DocumentValue.Mapping observed(Observation observation) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        if (observation instanceof final Visible visible) {
            held.put(VISIBILITY, new DocumentValue.Text(VISIBLE));
            held.put(VALUE, visible.value().document());
            return new DocumentValue.Mapping(held);
        }
        held.put(VISIBILITY, new DocumentValue.Text(REDACTED));
        return new DocumentValue.Mapping(held);
    }
}
