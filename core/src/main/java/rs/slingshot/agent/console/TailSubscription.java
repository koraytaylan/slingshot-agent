// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * Where a console follows a running operation, which is the route a client already follows it on.
 *
 * <p>The same route rather than a second stream, deliberately. A console watching its own stream
 * would be a second implementation of the hardest thing in this repository — resumption, ordering,
 * heartbeats, and the bound on how long one may stay open — and the day the two disagree, the
 * operator's console and the caller's client are showing different accounts of the same operation
 * and neither of them knows.</p>
 *
 * <p>Which also means the console is held to the same concurrency budget as every client. A tail
 * nobody is watching is a connection the instance is holding open for nobody, and an author has a
 * bounded number of them; the script closes it when the page goes away for exactly that reason.</p>
 */
public final class TailSubscription {

    private TailSubscription() {
    }

    /** The route events are followed on, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "events";

    /** The attribute the console's one script reads the operation from. */
    public static final String ATTRIBUTE = "data-slingshot-agent-operation";

    /**
     * Where that route sits, read from the committed table rather than written down again.
     *
     * <p>Written down here it would be a second spelling of a path, and the day somebody moves the
     * route the console would follow the old one and show an operator nothing happening.</p>
     *
     * @return the path
     */
    private static String routePath() {
        return AgentRouteTable.load() instanceof final AgentRouteTable.Loaded loaded
                ? loaded.table().route(ROUTE_NAME).path() : "";
    }

    /** What a viewer is offered: a subscription, or the reason there is none. */
    public sealed interface Offer permits Followable, Finished, Withheld {
    }

    /**
     * A running operation somebody may watch.
     *
     * @param address where to follow it
     */
    public record Followable(String address) implements Offer {
    }

    /**
     * One that has already ended.
     *
     * <p>Offered as nothing rather than as a stream that opens and immediately closes: a console
     * that opened a connection to say "this finished an hour ago" would be spending the instance's
     * bounded stream budget on saying nothing.</p>
     */
    public record Finished() implements Offer {
    }

    /** One this viewer may not follow, so the page does not offer it. */
    public record Withheld() implements Offer {
    }

    /** Whether the operation is still going. */
    public enum Progress {
        /** It is, so there is something to watch. */
        RUNNING,
        /** It is not, and there never will be anything more. */
        ENDED
    }

    /**
     * What to offer a viewer for one operation.
     *
     * @param operationIdentifier which operation
     * @param progress whether it is still going
     * @param admitted whether this viewer may follow it at all
     * @return the subscription, or the reason there is none
     */
    public static Offer offer(String operationIdentifier, Progress progress,
                              ConsoleAuthority.Visibility admitted) {
        if (admitted != ConsoleAuthority.Visibility.SHOWN) {
            return new Withheld();
        }
        return progress == Progress.ENDED
                ? new Finished()
                : new Followable(routePath() + "?operation="
                        + URLEncoder.encode(operationIdentifier, StandardCharsets.UTF_8));
    }
}
