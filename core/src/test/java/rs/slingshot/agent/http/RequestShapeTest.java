// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * Every way a route can be reached that is not the way it is served.
 *
 * <p>The corpus is generated from the committed table rather than written out, so a route added
 * tomorrow is held to the same five spellings without anybody remembering to add it here. That is
 * the difference between a suite that proves a property and one that proves the examples somebody
 * thought of.</p>
 */
final class RequestShapeTest {

    private static final AgentRouteTable TABLE = table();

    /** A method no route in this table answers, used to prove every route refuses one. */
    private static final String ANOTHER_METHOD = "PUT";

    /** A media type no route takes. */
    private static final String ANOTHER_MEDIA_TYPE = "text/csv";

    @Test
    @DisplayName("every route answers its own exact path and nothing else")
    void everyrouteAnswersItsExactPathOnly() {
        for (final AgentRoute route : TABLE.routes().values()) {
            assertInstanceOf(RequestShape.Accepted.class, exact(route).against(route),
                    route.name() + " does not answer the path the table gives it");
            for (final RequestShape spelling : alternativeSpellings(route)) {
                assertEquals(ShapeRefusal.NOT_THE_EXACT_PATH,
                        RequestShape.refusalIn(spelling.against(route)).orElseThrow(
                                () -> new AssertionError(route.name() + " answered "
                                        + spelling.spelled())).refusal(),
                        route.name() + " answered " + spelling.spelled());
            }
        }
        assertFalse(TABLE.routes().isEmpty(), "the table declares no routes at all");
    }

    @Test
    @DisplayName("every route answers one method and refuses the rest by name")
    void everyrouteAnswersOneMethod() {
        for (final AgentRoute route : TABLE.routes().values()) {
            final RequestShape.Refused refused = RequestShape.refusalIn(
                    exact(route).withMethod(ANOTHER_METHOD).against(route)).orElseThrow();
            assertEquals(ShapeRefusal.WRONG_METHOD, refused.refusal());
            assertTrue(refused.detail().contains(ANOTHER_METHOD)
                            && refused.detail().contains(route.method()),
                    refused.detail());
        }
    }

    @Test
    @DisplayName("a route takes one media type, or takes no body at all")
    void arouteTakesOneMediaTypeOrNone() {
        for (final AgentRoute route : TABLE.routes().values()) {
            if (route.takesABody()) {
                assertEquals(ShapeRefusal.WRONG_MEDIA_TYPE, RequestShape.refusalIn(
                        exact(route).withBody(ANOTHER_MEDIA_TYPE).against(route)).orElseThrow()
                        .refusal(), route.name() + " took a body in a type it does not take");
                assertEquals(ShapeRefusal.WRONG_MEDIA_TYPE, RequestShape.refusalIn(
                        exact(route).withoutABody().against(route)).orElseThrow().refusal(),
                        route.name() + " took a request with no body at all");
                assertInstanceOf(RequestShape.Accepted.class,
                        exact(route).withBody(route.mediaType() + "; charset=utf-8")
                                .against(route),
                        route.name() + " refused its own media type because of a parameter");
                continue;
            }
            assertEquals(ShapeRefusal.WRONG_MEDIA_TYPE, RequestShape.refusalIn(
                    exact(route).withBody(route.mediaType()).against(route)).orElseThrow()
                    .refusal(), route.name() + " took a body it does not take");
        }
    }

    @Test
    @DisplayName("the three refusals are three, each with its own answer on the wire")
    void thethreeRefusalsAreThree() {
        assertEquals(3, ShapeRefusal.values().length, "a refusal was added or lost");
        assertEquals(List.of(404, 405, 415), java.util.Arrays.stream(ShapeRefusal.values())
                        .map(ShapeRefusal::status).toList(),
                "a shape refusal answers with something other than the protocol's own answer");
        assertEquals(ShapeRefusal.values().length, java.util.Arrays.stream(ShapeRefusal.values())
                        .map(ShapeRefusal::spelling).distinct().count(),
                "two refusals are spelled the same");
        for (final ShapeRefusal refusal : ShapeRefusal.values()) {
            assertEquals(refusal, ShapeRefusal.named(refusal.spelling()).orElseThrow());
        }
        assertTrue(ShapeRefusal.named("something_else_entirely").isEmpty());
    }

    @Test
    @DisplayName("a shape is decided from what a request is, never from what it carries")
    void ashapeIsDecidedFromWhatArequestIs() {
        final String source = source();
        for (final String reading : List.of("getParameter", "getInputStream", "getReader",
                "getHeader", "getResourceResolver")) {
            assertFalse(source.contains(reading),
                    "the shape rules reach for " + reading + ", which is reading a request they"
                            + " may be about to refuse");
        }
        assertTrue(source.contains("route.path()"), "the path a route is reached at is not the"
                + " table's own");
    }

    @Test
    @DisplayName("a media type is compared without its parameters and without its case")
    void amediaTypeIsComparedPlainly() {
        final AgentRoute route = TABLE.routes().values().stream()
                .filter(AgentRoute::takesABody)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no route in the table takes a body"));
        assertEquals(route.mediaType(), exact(route)
                .withBody(route.mediaType().toUpperCase(Locale.ROOT) + " ; charset=utf-8")
                .named().orElseThrow(),
                "a media type in another case is not the same media type");
        assertTrue(exact(route).withoutABody().named().isEmpty(),
                "a request that says nothing about its body was said to name a type");
        assertInstanceOf(RequestShape.Accepted.class, exact(route)
                        .withBody(route.mediaType().toUpperCase(Locale.ROOT)).against(route),
                "a route refused its own media type spelled in another case");
    }

    private static List<RequestShape> alternativeSpellings(AgentRoute route) {
        return List.of(
                // Sling reports the registered path however the route was spelled, so each of
                // these carries the spelling the caller used beside what was resolved.
                new RequestShape(route.path() + ".json", route.path(), "json", "", "",
                        route.method(), "", RequestShape.Body.ABSENT),
                new RequestShape(route.path() + ".detail.json", route.path(), "", "json", "",
                        route.method(), "", RequestShape.Body.ABSENT),
                new RequestShape(route.path() + "/anything", route.path(), "", "", "/anything",
                        route.method(), "", RequestShape.Body.ABSENT),
                new RequestShape(route.path() + "/anything", route.path() + "/anything", "", "", "",
                        route.method(), "", RequestShape.Body.ABSENT),
                new RequestShape(route.path().toUpperCase(Locale.ROOT),
                        route.path().toUpperCase(Locale.ROOT), "", "", "", route.method(), "",
                        RequestShape.Body.ABSENT));
    }

    private static RequestShape exact(AgentRoute route) {
        return route.takesABody()
                ? new RequestShape(route.path(), route.path(), "", "", "", route.method(),
                        route.mediaType(), RequestShape.Body.PRESENT)
                : new RequestShape(route.path(), route.path(), "", "", "", route.method(), "",
                        RequestShape.Body.ABSENT);
    }

    private static String source() {
        return new String(bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] bytes() {
        try {
            return java.nio.file.Files.readAllBytes(repositoryRoot().resolve(
                    "core/src/main/java/rs/slingshot/agent/http/RequestShape.java"));
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException("the shape rules are not readable", unreadable);
        }
    }

    private static AgentRouteTable table() {
        return assertInstanceOf(AgentRouteTable.Loaded.class, AgentRouteTable.load(),
                "the route table was refused").table();
    }

    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path walked = java.nio.file.Path.of("").toAbsolutePath();
        while (walked != null && !java.nio.file.Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
