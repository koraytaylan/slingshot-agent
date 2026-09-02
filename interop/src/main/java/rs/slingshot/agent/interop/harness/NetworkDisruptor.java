// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A connection severed at a named moment, at the boundary rather than inside either process.
 *
 * <p>The interesting case in a transport is not success and not refusal. It is the one where a
 * request left and the answer did not come back, because that is the case where a command may or
 * may not have run — and a client that guessed either way would be wrong half the time.</p>
 *
 * <p>Severed with the linger interval at zero, which sends a reset rather than closing. That
 * matters: an orderly close is something both sides can flush through and read to the end of, and
 * proving anything about a lost answer requires that nobody got one.</p>
 */
public final class NetworkDisruptor implements AutoCloseable {

    /**
     * The interface a disruptor listens on, which is this machine and nothing beyond it.
     *
     * <p>Named rather than written as an address so that a disruptor cannot accidentally be
     * reachable from anywhere else: what it stands in front of is a container this suite started.
     * </p>
     */
    private static final String THIS_MACHINE = java.net.InetAddress.getLoopbackAddress()
            .getHostAddress();

    /** How many bytes of a head or a body count as "some of it" before the severance. */
    public static final int SOME_OF_IT = 64;

    /** Where a connection is severed, and there is no ninth. */
    public enum Point {
        /** Before the first request byte reaches the instance. */
        BEFORE_THE_FIRST_REQUEST_BYTE("before_the_first_request_byte"),
        /** After the request head and before its body. */
        AFTER_THE_REQUEST_HEAD("after_the_request_head"),
        /** After the whole request and before any of the answer. */
        AFTER_THE_BODY("after_the_body"),
        /** Part way through the answer's head. */
        MID_RESPONSE_HEAD("mid_response_head"),
        /** Part way through the answer's body. */
        MID_RESPONSE_BODY("mid_response_body"),
        /** Part way through an event stream, which resumes from a cursor. */
        MID_STREAM("mid_stream"),
        /** Part way through an artifact transfer, which restarts. */
        MID_ARTIFACT_TRANSFER("mid_artifact_transfer"),
        /** Part way through an artifact arriving, which leaves a slot claimable again. */
        MID_INTAKE("mid_intake");

        private final String spelling;

        Point(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this point is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }
    }

    /** What actually happened to one connection. */
    public enum Severance {
        /** It was severed at the point this disruptor was asked for. */
        SEVERED,
        /** Nothing connected at all, so nothing was severed. */
        NOTHING_CONNECTED
    }

    private final ServerSocketChannel listening;
    private final URI target;
    private final Point point;
    private final AtomicReference<Severance> severance =
            new AtomicReference<>(Severance.NOTHING_CONNECTED);
    private final Thread pump;

    private NetworkDisruptor(ServerSocketChannel listening, URI target, Point point) {
        this.listening = listening;
        this.target = target;
        this.point = point;
        this.pump = new Thread(this::forward, "slingshot-agent-network-disruptor");
        this.pump.setDaemon(true);
    }

    /**
     * Stands in front of a running instance, severing the next connection at one point.
     *
     * @param address where the instance is listening
     * @param point where to sever
     * @return the disruptor, listening on an address of its own
     */
    public static NetworkDisruptor inFrontOf(String address, Point point) {
        final NetworkDisruptor disruptor =
                new NetworkDisruptor(listener(), URI.create(address), point);
        disruptor.pump.start();
        return disruptor;
    }

    private static ServerSocketChannel listener() {
        try {
            return ServerSocketChannel.open().bind(new InetSocketAddress(THIS_MACHINE, 0));
        } catch (final IOException unbindable) {
            throw new UncheckedIOException("no address to sever a connection at", unbindable);
        }
    }

    /**
     * Where a caller reaches the instance through this disruptor.
     *
     * @return the address
     */
    public String address() {
        try {
            return "http://" + THIS_MACHINE + ":"
                    + ((InetSocketAddress) listening.getLocalAddress()).getPort();
        } catch (final IOException unbound) {
            throw new UncheckedIOException("this disruptor is listening nowhere", unbound);
        }
    }

    /**
     * What happened to the connection, once one has been made.
     *
     * @return whether it was severed
     */
    public Severance severance() {
        return severance.get();
    }

    /**
     * Where this disruptor severs.
     *
     * @return the point
     */
    public Point point() {
        return point;
    }

    /** Stops listening, whether or not anything ever connected. */
    @Override
    public void close() {
        try {
            listening.close();
        } catch (final IOException already) {
            // A listener that is already closed is closed, which is what was asked for, so the
            // only thing left to record is that nothing more will be severed here.
            severance.compareAndSet(Severance.NOTHING_CONNECTED, Severance.NOTHING_CONNECTED);
        }
        pump.interrupt();
    }

    private void forward() {
        // Every connection, not the first one: a client that retries an idempotent request after a
        // reset would otherwise reach a listener nobody is accepting on and wait out its own
        // timeout, which is a slow test rather than a severed one.
        while (listening.isOpen()) {
            severOne();
        }
    }

    private void severOne() {
        try (SocketChannel accepted = listening.accept()) {
            if (point == Point.BEFORE_THE_FIRST_REQUEST_BYTE) {
                sever(accepted);
                return;
            }
            try (SocketChannel upstream = SocketChannel.open(
                    new InetSocketAddress(target.getHost(), target.getPort()))) {
                exchange(accepted, upstream);
            }
        } catch (final IOException gone) {
            // The caller went away, or the listener was closed while waiting. Either way there is
            // nothing left to sever, and that is recorded rather than thrown from a thread nobody
            // is holding.
            severance.compareAndSet(Severance.NOTHING_CONNECTED, Severance.NOTHING_CONNECTED);
        }
    }

    private void exchange(SocketChannel accepted, SocketChannel upstream)
            throws IOException {
        final Thread request = new Thread(() -> pumpRequest(accepted, upstream));
        request.setDaemon(true);
        request.start();
        pumpResponse(accepted, upstream);
    }

    private void pumpRequest(SocketChannel accepted, SocketChannel upstream) {
        try {
            // Read and written through the channels rather than through streams held in locals: a
            // stream closed here would close the channel whoever severs is about to close, which
            // is the one thing this must not do out of turn.
            final byte[] buffer = new byte[SOME_OF_IT];
            final StringBuilder head = new StringBuilder();
            int read = Channels.newInputStream(accepted).read(buffer);
            while (read >= 0) {
                Channels.newOutputStream(upstream).write(buffer, 0, read);
                head.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                if (point == Point.AFTER_THE_REQUEST_HEAD && head.indexOf("\r\n\r\n") >= 0) {
                    sever(accepted, upstream);
                    return;
                }
                read = Channels.newInputStream(accepted).read(buffer);
            }
        } catch (final IOException gone) {
            // The connection this thread was pumping is the one that was severed, which is what
            // this disruptor exists to do rather than something to report.
            severance.compareAndSet(Severance.NOTHING_CONNECTED, Severance.NOTHING_CONNECTED);
        }
    }

    private void pumpResponse(SocketChannel accepted, SocketChannel upstream)
            throws IOException {
        final byte[] buffer = new byte[SOME_OF_IT];
        long forwarded = 0;
        final StringBuilder head = new StringBuilder();
        int read = Channels.newInputStream(upstream).read(buffer);
        while (read >= 0) {
            if (severedBefore(accepted, upstream, forwarded, head.indexOf("\r\n\r\n"))) {
                return;
            }
            Channels.newOutputStream(accepted).write(buffer, 0, read);
            forwarded = forwarded + read;
            head.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            read = Channels.newInputStream(upstream).read(buffer);
        }
    }

    /**
     * Whether this chunk of the answer is the one that never arrives.
     *
     * <p>Three positions. Before any of it, which is a caller that sent a whole request and got
     * nothing at all. Part way through the head, which is a caller holding bytes that are not yet
     * an answer. And part way through whatever the route was answering — the body where there is
     * one, and the head where there is not, because an instance that has nothing to serve on that
     * route answers without a body and there is no body to be part way through.</p>
     *
     * @param accepted the caller's connection
     * @param upstream the instance's connection
     * @param forwarded how much of the answer has already reached the caller
     * @param headEnd where the answer's head ended in what has been forwarded, or below zero
     * @return whether it was severed
     * @throws IOException if severing fails
     */
    private boolean severedBefore(SocketChannel accepted, SocketChannel upstream, long forwarded,
                                  int headEnd) throws IOException {
        final boolean afterTheBody = point == Point.AFTER_THE_BODY && forwarded == 0;
        final boolean partWayThrough = (point == Point.MID_RESPONSE_HEAD || midBody())
                && forwarded > 0 && (headEnd < 0 || midBody());
        if (afterTheBody || partWayThrough) {
            sever(accepted, upstream);
            return true;
        }
        return false;
    }

    private boolean midBody() {
        return point == Point.MID_RESPONSE_BODY || point == Point.MID_STREAM
                || point == Point.MID_ARTIFACT_TRANSFER || point == Point.MID_INTAKE;
    }

    private void sever(SocketChannel accepted, SocketChannel upstream) throws IOException {
        sever(accepted);
        upstream.setOption(StandardSocketOptions.SO_LINGER, 0);
        upstream.close();
    }

    /**
     * Severs the caller's connection where there is nothing upstream to sever.
     *
     * <p>Two entry points rather than one that takes an absence: severing before the first request
     * byte is a different situation from severing mid-exchange, and a caller that had to say "and
     * nothing upstream" would be a caller passing an absence around.</p>
     *
     * @param accepted the caller's connection
     * @throws IOException if severing fails
     */
    private void sever(SocketChannel accepted) throws IOException {
        // The linger interval at zero sends a reset rather than closing: an orderly close is
        // something both sides can flush through and read to the end of, and what is being proved
        // here is what happens when nobody got an answer at all.
        accepted.setOption(StandardSocketOptions.SO_LINGER, 0);
        accepted.close();
        severance.set(Severance.SEVERED);
    }
}
