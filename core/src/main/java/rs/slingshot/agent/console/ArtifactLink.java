// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.ArtifactRecord;

/**
 * Where a console sends somebody to fetch an artifact, which is the route the client already uses.
 *
 * <p>Never a repository path. The stores live where no person's session reaches them, so a path
 * would be either useless or — if it worked — a way to read the agent's own storage through a
 * console link. The route already decides who may fetch what and already reports a count and a
 * digest; sending people there means the console offers exactly what a client is offered and
 * nothing more.</p>
 *
 * <p>A swept artifact is shown as expired rather than linked. A link that fails when clicked is
 * worse than no link: it teaches an operator that downloads are broken, when what actually happened
 * is that the retention they configured did what they configured it to do.</p>
 */
public final class ArtifactLink {

    private ArtifactLink() {
    }

    /** The route an artifact is fetched from, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "artifact-transfer";

    /**
     * Where that route sits, read from the committed table rather than written down again.
     *
     * <p>Written down here it would be a second spelling of a path, and the day somebody moves the
     * route the console would go on offering the old one — which reads to an operator as a broken
     * download rather than as a link nobody updated.</p>
     *
     * @return the path
     */
    private static String routePath() {
        return AgentRouteTable.load() instanceof final AgentRouteTable.Loaded loaded
                ? loaded.table().route(ROUTE_NAME).path() : "";
    }

    /** What is offered for one artifact: a link, or the reason there is none. */
    public sealed interface Offer permits Linked, Expired, Withheld {
    }

    /**
     * A link a viewer may follow.
     *
     * @param address where to fetch it, which addresses the route by operation and slot
     * @param byteCount how large it is, so a downloader can check what they received
     * @param digest what it hashes to, so they can check it without trusting this page
     */
    public record Linked(String address, long byteCount, String digest) implements Offer {
    }

    /**
     * One retention has already taken.
     *
     * @param expiredAtUnixMilliseconds when it went
     */
    public record Expired(long expiredAtUnixMilliseconds) implements Offer {
    }

    /**
     * One this viewer would be refused, so the page does not offer it.
     *
     * <p>Not offering it is the point. A page that shows a link the route will deny sends somebody
     * to a refusal and leaves them thinking the download is broken rather than that it is not
     * theirs.</p>
     */
    public record Withheld() implements Offer {
    }

    /** Whether the artifact is still there. */
    public enum Retention {
        /** It is. */
        HELD,
        /** The sweep has taken it. */
        SWEPT
    }

    /**
     * What to offer a viewer for one artifact.
     *
     * @param operationIdentifier which operation it belongs to
     * @param record what the store holds about it
     * @param retention whether the sweep has taken it
     * @param admitted whether this viewer may fetch from the route at all
     * @return the link, or the reason there is none
     */
    public static Offer offer(String operationIdentifier, ArtifactRecord record,
                              Retention retention, ConsoleAuthority.Visibility admitted) {
        if (admitted != ConsoleAuthority.Visibility.SHOWN) {
            return new Withheld();
        }
        return retention == Retention.SWEPT
                ? new Expired(record.publishedAtUnixMilliseconds())
                : new Linked(addressOf(operationIdentifier, record.slot().name()),
                        record.byteCount(), record.digest().rendered());
    }

    /**
     * Where the route serves one artifact.
     *
     * @param operationIdentifier which operation
     * @param slot which slot of it
     * @return the address
     */
    public static String addressOf(String operationIdentifier, String slot) {
        return routePath() + "?operation=" + encoded(operationIdentifier) + "&slot=" + encoded(slot);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
