// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.repository;

import java.util.Map;
import java.util.function.Function;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

/**
 * The only place a session is obtained, and there are exactly two of them.
 *
 * <p>One for the agent's own bookkeeping, under a service user whose permissions are a list
 * somebody can read out loud. One that is the requesting user's own, which is not obtained at all —
 * it is the session the request already arrived with.</p>
 *
 * <p>There is deliberately no third. Nothing here impersonates, holds a credential, or stores a
 * token, and that is what makes the second method's guarantee unconditional rather than bounded: a
 * command executes inside the request that submitted it, so the caller's session is the request's
 * own and there is nothing to borrow. An agent that executed later would have needed a standing
 * privilege over other people's identities, and somebody would then have had to justify it.</p>
 */
public final class AgentSession {

    /** The subservice the durable store writes under. */
    public static final String STATE_SUBSERVICE = "state";

    /** The subservice the sweep and the recovery reconciliation read and collect under. */
    public static final String MAINTENANCE_SUBSERVICE = "maintenance";

    /** The one tree the agent's own identity may write, and the only one it is granted. */
    public static final String AGENT_TREE = "/var/slingshot-agent";

    /** How Sling is told which subservice a session is being asked for. */
    private static final String SUBSERVICE_KEY = ResourceResolverFactory.SUBSERVICE;

    /**
     * How a session for one of the agent's own subservices is opened.
     *
     * <p>This is deliberately narrower than the platform's own factory. The factory can also open
     * an administrative session, and a type that can do something nothing here may do is a type
     * somebody eventually uses to do it. What this agent needs is one thing, so this is one
     * method.</p>
     */
    @FunctionalInterface
    public interface ServiceSessionSource {

        /**
         * Opens a session for one of the agent's own subservices.
         *
         * @param subservice which subservice is asking
         * @return the session, which the caller closes
         * @throws LoginException if the platform will not grant one, which is a mapping that is not
         *     installed or a repository that is not ready
         */
        ResourceResolver open(String subservice) throws LoginException;
    }

    private final ServiceSessionSource sessions;

    /**
     * Holds the one way this agent opens a session of its own.
     *
     * @param sessions where the agent's own sessions come from
     */
    public AgentSession(ServiceSessionSource sessions) {
        this.sessions = sessions;
    }

    /**
     * Builds one that opens its sessions through the platform's own factory.
     *
     * <p>This is the only place the platform's factory is held, and it is held behind a method that
     * can do exactly one thing with it.</p>
     *
     * @param factory the platform's own resolver factory
     * @return an agent session that opens service sessions through it
     */
    public static AgentSession usingPlatform(ResourceResolverFactory factory) {
        return new AgentSession(subservice ->
                factory.getServiceResourceResolver(Map.of(SUBSERVICE_KEY, subservice)));
    }

    /** Why a session could not be obtained. */
    public enum Failure {
        /** The service user mapping does not name this subservice, so nothing was granted. */
        UNMAPPED_SUBSERVICE,
        /** The platform refused the login, which is a repository that is not ready or not there. */
        REFUSED_BY_THE_PLATFORM
    }

    /**
     * The result of working under the agent's own identity: what the work answered, or the one
     * reason it never ran.
     *
     * @param <T> what the work answers
     */
    public sealed interface Outcome<T> permits Completed, Refused {
    }

    /**
     * Work that ran under the agent's own identity and answered.
     *
     * @param result what the work answered
     * @param <T> what the work answers
     */
    public record Completed<T>(T result) implements Outcome<T> {
    }

    /**
     * An ask that produced no session, so the work never ran.
     *
     * @param failure why there is none
     * @param detail what was refused, named so that somebody can fix it
     * @param <T> what the work would have answered
     */
    public record Refused<T>(Failure failure, String detail) implements Outcome<T> {
    }

    /**
     * Runs work under the agent's own identity, on the agent's own tree and nothing else.
     *
     * <p>The session never leaves this method. That is the whole design: a session handed back is a
     * session somebody has to remember to close, and the close they write in a trailing block is
     * the one an early return skips. Here the language's own resource management closes it on every
     * path, including the ones nobody wrote down.</p>
     *
     * @param subservice which of the agent's own subservices is asking
     * @param work what to do with the session
     * @param <T> what the work answers
     * @return what the work answered, or the one reason it never ran
     */
    public <T> Outcome<T> withAgentState(String subservice, Function<ResourceResolver, T> work) {
        if (!STATE_SUBSERVICE.equals(subservice) && !MAINTENANCE_SUBSERVICE.equals(subservice)) {
            return new Refused<>(Failure.UNMAPPED_SUBSERVICE, subservice);
        }
        try (ResourceResolver resolver = sessions.open(subservice)) {
            return new Completed<>(work.apply(resolver));
        } catch (final LoginException refused) {
            return new Refused<>(Failure.REFUSED_BY_THE_PLATFORM,
                    String.valueOf(refused.getMessage()));
        }
    }

    /**
     * The session the request already arrived with, which is the requesting user's own.
     *
     * <p>Nothing is obtained here and nothing is closed: the platform owns this session, opened it
     * for whoever authenticated, and will close it when the request ends. Every permission it
     * carries is theirs, which is why a command running inside it cannot do anything its caller
     * could not have done directly.</p>
     *
     * @param request the request as Sling resolved it
     * @return the requesting user's own session
     */
    public static ResourceResolver forCaller(SlingHttpServletRequest request) {
        return request.getResourceResolver();
    }
}
