// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Talking to a running instance over the network.
 *
 * <p>Everything a tier says to an instance goes through here, so a tier is about what it installs
 * and what it asks rather than about how a request is built. What is left in here is transport: the
 * interruptions, the connection failures, and the shape of a multipart upload - branches a suite
 * cannot reach deterministically, which is exactly why they are together in one place rather than
 * spread through the tier that would then be judged on them.</p>
 */
public final class TierRequests {

    /** How long establishing a connection to a starting instance may take. */
    private static final int CONNECT_SECONDS = 5;

    /** How long one request to a running instance may take. */
    private static final int REQUEST_SECONDS = 30;

    /** The user a Sling runtime authenticates by default, which is somebody in particular. */
    private static final String AUTHENTICATED_USER = "admin";

    private static final String AUTHENTICATED_PASSWORD = "admin";

    /**
     * A caller the platform authenticates and no operator has permitted.
     *
     * <p>Kept apart from the caller above because "outside every permitted group" has to be
     * somebody who is, rather than whoever the runtime happens not to have put in one. A tier that
     * proved it with the ordinary caller would stop proving it the moment that caller was
     * permitted, and would go on passing.</p>
     */
    public static final String UNPERMITTED_USER = "slingshot-unpermitted";

    /** What that caller authenticates with, which is no secret and guards nothing. */
    public static final String UNPERMITTED_PASSWORD = "slingshot-unpermitted";

    /** How far apart a field's name and its value sit, which is what makes them a pair. */
    private static final int PAIR = 2;

    private final HttpClient client;

    private TierRequests(HttpClient client) {
        this.client = client;
    }

    /**
     * Builds the one client every request a tier makes goes through.
     *
     * @return the requests
     */
    public static TierRequests open() {
        return new TierRequests(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
                .build());
    }

    /**
     * Reads something as a user the instance authenticated.
     *
     * @param address where to read it
     * @return what the instance answered
     */
    public HttpResponse<String> readAsAuthenticatedUser(String address) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .header("Authorization", basic())
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .GET()
                .build());
    }

    /**
     * Sends a document to a route as the authenticated user.
     *
     * @param address where to send it
     * @param document the bytes to send
     * @param mediaType what to say the bytes are
     * @return what the instance answered
     */
    public HttpResponse<String> postAsAuthenticatedUser(String address, String document,
                                                        String mediaType) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .header("Authorization", basic())
                .header("Content-Type", mediaType)
                .header("Referer", address)
                .POST(HttpRequest.BodyPublishers.ofString(document))
                .build());
    }

    /**
     * Sends a document to a route as a caller the platform authenticates and nobody has permitted.
     *
     * <p>The same request as the one above in every respect but who sends it, so what the answer
     * differs by is the caller's standing and nothing else.</p>
     *
     * @param address where to send it
     * @param document what to send
     * @param mediaType what it is
     * @return what the instance answered
     */
    public HttpResponse<String> postAsUnpermittedUser(String address, String document,
                                                      String mediaType) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .header("Authorization", basicFor(UNPERMITTED_USER, UNPERMITTED_PASSWORD))
                .header("Content-Type", mediaType)
                .header("Referer", address)
                .POST(HttpRequest.BodyPublishers.ofString(document))
                .build());
    }

    /**
     * Sends a document to a route as nobody in particular.
     *
     * @param address where to send it
     * @param document the bytes to send
     * @param mediaType what to say the bytes are
     * @return what the instance answered
     */
    public HttpResponse<String> postAsNobody(String address, String document, String mediaType) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .header("Content-Type", mediaType)
                .header("Referer", address)
                .POST(HttpRequest.BodyPublishers.ofString(document))
                .build());
    }

    /**
     * Submits form fields with a referrer of the caller's own choosing.
     *
     * <p>The platform refuses a state-changing request whose referrer it does not allow, before any
     * servlet is reached. A suite that only ever sent one shape of request could not tell that
     * apart from a servlet's own refusal.</p>
     *
     * @param address where to submit
     * @param fields the form fields, as name and value in turn
     * @param referrer what to name as the referrer
     * @return what the instance answered
     */
    public HttpResponse<String> submitWithReferrer(String address, List<String> fields,
                                                   String referrer) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .header("Authorization", basic())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", referrer)
                .POST(HttpRequest.BodyPublishers.ofString(encoded(fields)))
                .build());
    }

    /**
     * Reads something as somebody the platform will not accept.
     *
     * <p>Kept beside reading as nobody because the two answers have to be identical: a caller who
     * can tell "there is no such user" from "that is not their password" has been told which names
     * exist.</p>
     *
     * @param address where to read it
     * @return what the instance answered
     */
    public HttpResponse<String> readAsUnknownUser(String address) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "nobody-this-instance-knows:not-a-password"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .GET()
                .build());
    }

    /**
     * Reads something as nobody in particular.
     *
     * @param address where to read it
     * @return what the instance answered
     */
    public HttpResponse<String> readAsNobody(String address) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .GET()
                .build());
    }

    /**
     * Hands a file to an instance as a multipart upload.
     *
     * <p>The field the file arrives under is the caller's, because the two things this repository
     * hands to an instance disagree about it: a platform console takes a bundle under the name it
     * publishes, and a content repository takes a file under the name of the node it is to become.
     * </p>
     *
     * @param address where to hand it over
     * @param fields the form fields to send with it, as name and value in turn
     * @param fileField the field the file itself arrives under
     * @param file the file itself
     * @return what the instance answered
     */
    public HttpResponse<String> upload(String address, List<String> fields, String fileField,
                                       Path file) {
        final String boundary = "slingshot-agent-" + System.nanoTime();
        return send(HttpRequest.newBuilder(URI.create(address))
                .header("Authorization", basic())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(boundary, fields, fileField, file)))
                .build());
    }

    /**
     * Hands form fields to an instance as a user it authenticated.
     *
     * @param address where to hand them over
     * @param fields the fields, as name and value in turn
     * @return what the instance answered
     */
    public HttpResponse<String> submit(String address, List<String> fields) {
        return send(HttpRequest.newBuilder(URI.create(address))
                .header("Authorization", basic())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(encoded(fields)))
                .build());
    }

    private static String encoded(List<String> fields) {
        return IntStream.iterate(0, index -> index + 1 < fields.size(), index -> index + PAIR)
                .mapToObj(index -> URLEncoder.encode(fields.get(index), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(fields.get(index + 1), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /**
     * Whether a request to an address is answered with a status below a bound.
     *
     * <p>One method for both questions a readiness condition asks: whether the console is answering
     * at all, and whether the runtime has finished starting. Two methods that differed only in a
     * number would eventually differ in something else.</p>
     *
     * @param address what to ask for
     * @param below the status the answer must be under
     * @return whether it answered under that status
     */
    public boolean respondsBelow(String address, int below) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(address))
                            .header("Authorization", basic())
                            .timeout(Duration.ofSeconds(CONNECT_SECONDS))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() < below;
        } catch (final IOException notYet) {
            return false;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether handing form fields to an address is answered with a status below a bound.
     *
     * <p>A runtime that reads before it writes is a runtime a readiness condition can pass on and
     * then be refused by, so what a tier is about to do is what a tier waits for.</p>
     *
     * @param address where to hand them over
     * @param fields the fields, as name and value in turn
     * @param below the status the answer must be under
     * @return whether it answered under that status
     */
    public boolean submitRespondsBelow(String address, List<String> fields, int below) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(address))
                            .header("Authorization", basic())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(CONNECT_SECONDS))
                            .POST(HttpRequest.BodyPublishers.ofString(encoded(fields)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() < below;
        } catch (final IOException notYet) {
            return false;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the request was interrupted", interrupted);
        }
    }

    private static byte[] multipart(String boundary, List<String> fields, String fileField,
                                    Path file) {
        final String separator = "--" + boundary + "\r\n";
        final String head = IntStream.iterate(0, index -> index + 1 < fields.size(),
                        index -> index + PAIR)
                .mapToObj(index -> separator
                        + "Content-Disposition: form-data; name=\"" + fields.get(index)
                        + "\"\r\n\r\n" + fields.get(index + 1) + "\r\n")
                .collect(Collectors.joining())
                + separator
                + "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                + file.getFileName() + "\"\r\n"
                + "Content-Type: application/java-archive\r\n\r\n";
        final byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        final byte[] fileBytes = read(file);
        final byte[] tailBytes = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        final byte[] body = new byte[headBytes.length + fileBytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(fileBytes, 0, body, headBytes.length, fileBytes.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + fileBytes.length, tailBytes.length);
        return body;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String basic() {
        return basicFor(AUTHENTICATED_USER, AUTHENTICATED_PASSWORD);
    }

    private static String basicFor(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
