// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** Owned configuration uploads wait for registration while preserving ordinary refusals. */
final class ConfigurationUploadReadinessTest {

    @Test
    void aRegisteredUploadReturnsItsFirstAnswer() {
        final AtomicInteger calls = new AtomicInteger();
        final HttpResponse<String> answer = PublicSlingTier.configurationUpload(attempt -> {
            calls.incrementAndGet();
            return new Answer(201, "created");
        });
        assertEquals(201, answer.statusCode());
        assertEquals(1, calls.get());
    }

    @Test
    void aMissingServletIsRetriedUntilItAnswers() {
        final AtomicInteger calls = new AtomicInteger();
        final HttpResponse<String> answer = PublicSlingTier.configurationUpload(attempt -> {
            calls.incrementAndGet();
            return new Answer(attempt == 0 ? 404 : 201, "synthetic response");
        });
        assertEquals(201, answer.statusCode());
        assertEquals(2, calls.get());
    }

    @Test
    void theExistingPostingServletRegistrationMarkerIsRetried() {
        final AtomicInteger calls = new AtomicInteger();
        final HttpResponse<String> answer = PublicSlingTier.configurationUpload(attempt -> {
            calls.incrementAndGet();
            return attempt == 0 ? new Answer(500, "UnsupportedOperationException")
                    : new Answer(201, "created");
        });
        assertEquals(201, answer.statusCode());
        assertEquals(2, calls.get());
    }

    @Test
    void anOrdinaryServerErrorRemainsARefusalWithoutAReplay() {
        final AtomicInteger calls = new AtomicInteger();
        final HttpResponse<String> answer = PublicSlingTier.configurationUpload(attempt -> {
            calls.incrementAndGet();
            return new Answer(500, "ordinary server error");
        });
        assertEquals(500, answer.statusCode());
        assertEquals(1, calls.get());
    }

    @Test
    void anAbsentServletStillRefusesAtTheExistingInstallationCeiling() {
        final AtomicInteger calls = new AtomicInteger();
        final HttpResponse<String> answer = PublicSlingTier.configurationUpload(attempt -> {
            calls.incrementAndGet();
            return new Answer(404, "not found");
        });
        assertEquals(404, answer.statusCode());
        assertEquals(60, calls.get());
    }

    private record Answer(int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(uri()).build();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://synthetic.invalid/");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
