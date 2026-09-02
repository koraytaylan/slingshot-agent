// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;

/**
 * What answers questions about the replication agents and acts on their queues.
 *
 * <p>A transport address never crosses this seam. An agent's transport is a URL, and a URL to a
 * publish instance very frequently carries the credential it authenticates with — which means a
 * listing of agents is one of the easiest places in an author instance to collect passwords. What
 * an operator actually needs is which agent, whether it is on, whether its queue is stuck, and how
 * much is in it, and none of those is the address.</p>
 */
public interface ReplicationInventory {

    /** What kind of thing an agent's transport does, which is not the same as where it points. */
    enum TransportKind {
        /** It sends content to a publish instance. */
        PUBLISH("publish"),
        /** It clears a cache in front of one. */
        FLUSH("flush"),
        /** It brings content back from one. */
        REVERSE("reverse"),
        /** It writes to a fixed place. */
        STATIC("static");

        private final String spelling;

        TransportKind(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How the wire spells this kind.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The kind one spelling names.
         *
         * @param spelled what was written
         * @return the kind, or nothing where nothing is spelled that way
         */
        public static java.util.Optional<TransportKind> named(String spelled) {
            return java.util.Arrays.stream(values())
                    .filter(kind -> kind.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Every kind, spelled as the wire spells it.
         *
         * @return the spellings, in declaration order
         */
        public static List<String> spellings() {
            return java.util.Arrays.stream(values()).map(TransportKind::spelling).toList();
        }
    }

    /** What one replication action does to the content it names. */
    enum Action {
        /** Sends it. */
        ACTIVATE("activate"),
        /** Takes it away. */
        DEACTIVATE("deactivate"),
        /** Removes it. */
        DELETE("delete"),
        /** Checks the transport works, carrying no content. */
        TEST("test");

        private final String spelling;

        Action(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How the wire spells this action.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The action one spelling names.
         *
         * @param spelled what was written
         * @return the action, or nothing where nothing is spelled that way
         */
        public static java.util.Optional<Action> named(String spelled) {
            return java.util.Arrays.stream(values())
                    .filter(action -> action.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Every action, spelled as the wire spells it.
         *
         * @return the spellings, in declaration order
         */
        public static List<String> spellings() {
            return java.util.Arrays.stream(values()).map(Action::spelling).toList();
        }
    }

    /** Whether an agent is switched on. */
    enum Switch {
        /** It is. */
        ENABLED,
        /** It is not, which is one of the two reasons nothing is being replicated. */
        DISABLED
    }

    /** Whether a queue has stopped moving. */
    enum Flow {
        /** It is moving, or has nothing to move. */
        MOVING,
        /** It has stopped, which is the other reason nothing is being replicated. */
        BLOCKED
    }

    /**
     * One replication agent, with no transport address anywhere in it.
     *
     * @param agentIdentifier what the platform calls it
     * @param title what a person calls it
     * @param repositoryPath where its configuration is held, so an operator can go and read it
     * @param transportKind what kind of thing its transport does
     * @param switched whether the agent is on
     * @param flow whether its queue is moving
     * @param queuedEntryCount how much is waiting in it
     */
    record Agent(String agentIdentifier, String title, String repositoryPath,
                 TransportKind transportKind, Switch switched, Flow flow, long queuedEntryCount) {
    }

    /**
     * One entry waiting in a queue.
     *
     * @param entryIdentifier what the platform calls it
     * @param action what it would do to the content
     * @param contentPath what content it is about
     * @param attemptCount how many times it has been tried
     * @param lastFailureCategory why it last failed, or {@link #NEVER_FAILED}
     */
    record Entry(String entryIdentifier, Action action, String contentPath, long attemptCount,
                 String lastFailureCategory) {
    }

    /** What an entry says when it has not failed. */
    String NEVER_FAILED = "";

    /** What one replication call produced. */
    sealed interface Outcome permits Agents, Inspected, Queue, Flushed, Resubmitted, Refused {
    }

    /**
     * The agents a listing found.
     *
     * @param agents what it found, in the platform's own order
     */
    record Agents(List<Agent> agents) implements Outcome {

        /** Holds the agents apart from whatever produced them. */
        public Agents {
            agents = List.copyOf(agents);
        }
    }

    /**
     * One agent in full.
     *
     * @param agent what it is
     * @param retryDelayMilliseconds how long it waits before trying a failed entry again
     */
    record Inspected(Agent agent, long retryDelayMilliseconds) implements Outcome {
    }

    /**
     * What is in one queue.
     *
     * @param flow whether it is moving
     * @param entries what is waiting, in the platform's own order
     */
    record Queue(Flow flow, List<Entry> entries) implements Outcome {

        /** Holds the entries apart from whatever produced them. */
        public Queue {
            entries = List.copyOf(entries);
        }
    }

    /**
     * A queue that was emptied.
     *
     * @param removedEntryCount how much went
     */
    record Flushed(long removedEntryCount) implements Outcome {
    }

    /**
     * An entry that was offered again.
     *
     * @param resubmission whether the platform took it
     */
    record Resubmitted(Resubmission resubmission) implements Outcome {
    }

    /** Whether the platform took an entry back into its queue. */
    enum Resubmission {
        /** It did, and the entry will be tried again. */
        TAKEN,
        /** It did not, and the entry is where it was. */
        DECLINED
    }

    /**
     * The platform would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said, carrying no transport address
     */
    record Refused(String category, String detail) implements Outcome {
    }

    /**
     * Every replication agent this instance holds.
     *
     * @return what it found, or the reason there is nothing
     */
    Outcome agents();

    /**
     * One agent in full.
     *
     * @param agentIdentifier which agent
     * @return what it is, or the reason there is nothing
     */
    Outcome inspect(String agentIdentifier);

    /**
     * What is waiting in one agent's queue.
     *
     * @param agentIdentifier which agent
     * @return what is in it, or the reason there is nothing
     */
    Outcome queue(String agentIdentifier);

    /**
     * Empties one agent's queue, discarding work somebody expected to happen.
     *
     * @param agentIdentifier which agent
     * @param expectation how many entries the caller believes are in it, or {@link #ANY_COUNT}
     * @return how much went, or the reason nothing did
     */
    Outcome flush(String agentIdentifier, long expectation);

    /** What the expectation says when the caller stated none. */
    long ANY_COUNT = -1;

    /**
     * Offers one stuck entry to its queue again.
     *
     * @param agentIdentifier which agent
     * @param entryIdentifier which entry
     * @return whether the platform took it, or the reason nothing happened
     */
    Outcome retry(String agentIdentifier, String entryIdentifier);
}
