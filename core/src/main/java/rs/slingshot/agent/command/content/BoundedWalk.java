// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.sling.api.resource.Resource;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.stream.ElapsedTime;

/**
 * A depth-first walk of one subtree inside the caller's node and time budgets.
 *
 * <p>Every handler that has to look at each node below an anchor walks the same way, and the ones
 * that each wrote their own walk were the ones with no bound at all: a recursive descent through a
 * site or an asset library that ran until the request it answered on had been given up. So there
 * is one walk, iterative rather than recursive so a deep tree cannot end the request with a stack
 * overflow, and it stops the moment either the number of nodes it has read or the time it has taken
 * is past what the caller was granted. A walk that stopped says so, and its handler refuses rather
 * than answering with the part it saw, because a partial list reads as the whole of what is there.
 * </p>
 */
final class BoundedWalk {

    private BoundedWalk() {
    }

    /**
     * Visits the root and every node below it the walk may open, depth first.
     *
     * @param root where the walk starts, which is visited and counted first
     * @param context the caller's budgets
     * @param opens whether the walk goes into one node's children
     * @param visitor what is done with each node visited
     * @return whether every node was visited; false where a budget ran out first
     */
    static boolean every(Resource root, CallerContext context, Predicate<Resource> opens,
                         Consumer<Resource> visitor) {
        return every(root, context, opens, visitor, ElapsedTime.start());
    }

    /**
     * Visits a subtree using the supplied monotonic duration.
     *
     * @param root the first counted resource
     * @param context the execution budgets
     * @param opens whether children may be opened
     * @param visitor the action applied to each resource
     * @param elapsed the duration shared by this complete traversal
     * @return whether the traversal completed within its budgets
     */
    static boolean every(Resource root, CallerContext context, Predicate<Resource> opens,
                         Consumer<Resource> visitor, ElapsedTime elapsed) {
        final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
        long examined = 1;
        if (context.exceeded(examined, elapsed.milliseconds(), 0).isPresent()) {
            return false;
        }
        visitor.accept(root);
        if (opens.test(root)) {
            pending.push(root.listChildren());
        }
        while (!pending.isEmpty()) {
            final Iterator<Resource> children = pending.peek();
            if (!children.hasNext()) {
                pending.pop();
                continue;
            }
            examined++;
            if (context.exceeded(examined, elapsed.milliseconds(), 0).isPresent()) {
                return false;
            }
            final Resource next = children.next();
            visitor.accept(next);
            if (opens.test(next)) {
                pending.push(next.listChildren());
            }
        }
        return context.exceeded(examined, elapsed.milliseconds(), 0).isEmpty();
    }
}
