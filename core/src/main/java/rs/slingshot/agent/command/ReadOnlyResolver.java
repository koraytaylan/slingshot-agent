// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Map;
import javax.jcr.Session;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;

/**
 * What a read command is given, which cannot write whatever it does with it.
 *
 * <p>"This command replaces nothing" is a claim in a table until a machine enforces it. A read
 * handler that commits three frames down through a helper is the one that gets past review, so the
 * refusal is in the resolver rather than in a rule about handlers: a commit throws, a refresh that
 * would discard throws, and an adaptation that yields something able to write yields nothing.</p>
 *
 * <p>What {@code Read} claims is precise and worth stating precisely: the command replaces nothing
 * <em>the caller owns</em>. Scratch space inside this agent's own tree is a different tree, written
 * by framework-owned code under the service user through a staging area rather than by the handler
 * — and a read command that has one still cannot reach the caller's repository with anything but
 * this resolver.</p>
 */
public final class ReadOnlyResolver extends ResourceResolverWrapper {

    /** What a read command is told when it tries to write through its own resolver. */
    public static final String REFUSAL =
            "this command is declared read, and a read command replaces nothing the caller owns";

    private ReadOnlyResolver(ResourceResolver wrapped) {
        super(wrapped);
    }

    /**
     * Wraps the caller's own resolver so a read command cannot write through it.
     *
     * @param caller the requesting user's own resolver
     * @return the resolver a read handler is given
     */
    public static ResourceResolver around(ResourceResolver caller) {
        return new ReadOnlyResolver(caller);
    }

    /**
     * Refuses a commit.
     *
     * @throws PersistenceException always, because a read command replaces nothing
     */
    @Override
    public void commit() throws PersistenceException {
        throw new PersistenceException(REFUSAL);
    }

    /**
     * Refuses a refresh, which would discard whatever a handler had already staged in the session.
     *
     * <p>Refused rather than ignored: a handler that called it and carried on would be a handler
     * working against a view somebody else changed underneath it, which is a subtler failure than
     * a write.</p>
     */
    @Override
    public void refresh() {
        throw new UnsupportedOperationException(REFUSAL);
    }

    /**
     * Answers nothing for an adaptation that would yield something able to write.
     *
     * <p>The session is the one that matters: a handler that adapted to one would have every
     * write the caller has. Everything else adapts as it always did, because a read command that
     * could not read is not a read command.</p>
     *
     * @param type what to adapt to
     * @param <AdapterType> what to adapt to
     * @return the adaptation, or nothing where it would be able to write
     */
    @Override
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        return Session.class.equals(type) ? null : super.adaptTo(type);
    }

    /**
     * Refuses a delete.
     *
     * @param resource what would have been deleted
     * @throws PersistenceException always
     */
    @Override
    public void delete(Resource resource) throws PersistenceException {
        throw new PersistenceException(REFUSAL);
    }

    /**
     * Refuses a create.
     *
     * @param parent where it would have gone
     * @param name what it would have been called
     * @param properties what it would have held
     * @return nothing, ever
     * @throws PersistenceException always
     */
    @Override
    public Resource create(Resource parent, String name, Map<String, Object> properties)
            throws PersistenceException {
        throw new PersistenceException(REFUSAL);
    }

    /**
     * Refuses a move.
     *
     * @param from where it is
     * @param to where it would have gone
     * @return nothing, ever
     * @throws PersistenceException always
     */
    @Override
    public Resource move(String from, String to) throws PersistenceException {
        throw new PersistenceException(REFUSAL);
    }

    /**
     * Refuses a copy.
     *
     * @param from where it is
     * @param to where it would have gone
     * @return nothing, ever
     * @throws PersistenceException always
     */
    @Override
    public Resource copy(String from, String to) throws PersistenceException {
        throw new PersistenceException(REFUSAL);
    }

    /**
     * Refuses a revert, which is a write to the session even though it undoes one.
     */
    @Override
    public void revert() {
        throw new UnsupportedOperationException(REFUSAL);
    }

    /**
     * Whether anything is staged, which is always nothing here.
     *
     * @return false, because nothing can have been staged through this
     */
    @Override
    public boolean hasChanges() {
        return false;
    }

    /**
     * Refuses a second resolver of its own.
     *
     * <p>A clone carries the same authentication and none of this wrapper, so a handler that could
     * make one could write with it. That is the whole guarantee, one method away.</p>
     *
     * @param authentication what it would have been cloned with
     * @return nothing, ever
     * @throws UnsupportedOperationException always
     */
    @Override
    public ResourceResolver clone(Map<String, Object> authentication) {
        throw new UnsupportedOperationException(REFUSAL);
    }
}
