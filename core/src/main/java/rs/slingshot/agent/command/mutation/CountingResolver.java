// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * The caller's own resolver, counting the commits a handler makes through it.
 *
 * <p>Counted here rather than reviewed in a handler, because "this command commits once" is either
 * a property of the machinery or a thing somebody remembered. A process that stops between two
 * commits leaves a repository in a state no argument described, and the second commit is the one
 * nobody tests.</p>
 *
 * <p>The second commit is also refused outright rather than merely counted. A handler that would
 * have made one is stopped before it does, so the repository never reaches the state the count
 * would afterwards report — and the count still records the attempt, so a handler that swallowed
 * the refusal cannot hide it.</p>
 */
public final class CountingResolver extends ResourceResolverWrapper {

    /** What a handler is told when it tries to commit a second time. */
    public static final String REFUSAL =
            "this command has already committed; a mutation is one commit or none";

    private final AtomicLong commits = new AtomicLong();

    private CountingResolver(ResourceResolver wrapped) {
        super(wrapped);
    }

    /**
     * Wraps the caller's own resolver for the length of one command.
     *
     * @param caller the requesting user's own resolver
     * @return the resolver a mutation handler is given
     */
    public static CountingResolver around(ResourceResolver caller) {
        return new CountingResolver(caller);
    }

    /**
     * Commits, once.
     *
     * @throws PersistenceException where this command has already committed, or the repository
     *     refuses the commit
     */
    @Override
    public void commit() throws PersistenceException {
        if (commits.get() > 0) {
            commits.incrementAndGet();
            throw new PersistenceException(REFUSAL);
        }
        super.commit();
        // Counted after the repository accepted it. A commit that threw did not happen, and
        // counting the attempt would make an interrupted mutation look like a completed one.
        commits.incrementAndGet();
    }

    /**
     * How many commits this command made, attempted second ones included.
     *
     * @return the count
     */
    public long commits() {
        return commits.get();
    }
}
