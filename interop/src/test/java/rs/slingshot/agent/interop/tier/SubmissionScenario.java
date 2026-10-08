// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The route that starts work, asked for on a running instance.
 *
 * <p>What a running instance adds to the unit suite is everything between a client and a servlet:
 * that the route is registered at all, that the platform's own authentication reaches it, that a
 * body arrives as bytes rather than as parameters, and that a refusal comes back as a status with
 * nothing in it. An authenticated submission and its identical resend also exercise request-local
 * numeric timing headers through the installed servlet and the actual HTTP transport.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class SubmissionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario asks for, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a submission this side will not consider is answered with. */
    private static final int REFUSED = 400;

    /** What a request from nobody in particular is answered with. */
    private static final int UNAUTHORIZED = 401;

    /** What a request in a media type this route does not take is answered with. */
    private static final int UNSUPPORTED_MEDIA_TYPE = 415;

    /** What a caller nobody permitted is answered with. */
    private static final int FORBIDDEN = 403;

    /** An acknowledgement from the durable submission route. */
    private static final int ACCEPTED = 202;

    private static final int OK = 200;

    private static final String SERVER_TIMING = "Server-Timing";

    /**
     * The group a fresh install permits, which an operator widens by naming further ones.
     *
     * <p>The public tier places its administrator in this group before testing. The separate
     * unpermitted caller is authenticated by the same runtime and belongs to none of the groups
     * the submission route permits.</p>
     */
    private static final String PERMITTED_GROUP = "administrators";

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("the route is registered and refuses a submission from nobody in particular")
    void therouteIsRegisteredAndRefusesNobody() {
        final HttpResponse<String> anonymous = requests.postAsNobody(tier.address() + ROUTE,
                submission(), "application/json");
        assertEquals(UNAUTHORIZED, anonymous.statusCode(),
                "a submission from nobody in particular was not refused: " + anonymous.body());
        assertTrue(anonymous.body() == null || anonymous.body().isEmpty(),
                "a refusal carried a body: " + anonymous.body());
        assertTrue(anonymous.headers().allValues(SERVER_TIMING).isEmpty(),
                "an unauthenticated response carried submission timing");
    }

    @Test
    @DisplayName("accepted requests carry numeric timing and a resend carries no command execution")
    void acceptedRequestsAndResendsHaveIndependentNumericTimings() {
        final String document = currentSubmission();
        final HttpResponse<String> first = requests.postAsAuthenticatedUser(tier.address() + ROUTE,
                document, "application/json");
        assertEquals(ACCEPTED, first.statusCode(), () -> "the authenticated fixture was not accepted; "
                + admissionDiagnostics());
        assertNumericTiming(first);
        final DocumentValue.Mapping completed = snapshot(first.body());
        assertEquals(new DocumentValue.Text("succeeded"), completed.member("kind").orElseThrow());
        assertEquals(new DocumentValue.Whole(1), completed.member("attempt").orElseThrow());
        final HttpResponse<String> again = requests.postAsAuthenticatedUser(tier.address() + ROUTE,
                document, "application/json");
        assertEquals(ACCEPTED, again.statusCode());
        assertNumericTiming(again);
        assertTrue(again.headers().firstValue(SERVER_TIMING).orElseThrow()
                .endsWith(",execution;dur=0,persistence;dur=0"),
                "a terminal resend claimed to execute or persist the command again");
        assertEquals(first.body().replace("\"already_accepted\":false",
                "\"already_accepted\":true"), again.body(),
                "request timing changed the durable acknowledgement");
        assertEquals(completed, snapshot(again.body()),
                "a terminal resend changed the stored outcome or execution attempt");
    }

    private static void assertNumericTiming(HttpResponse<String> response) {
        final List<String> values = response.headers().allValues(SERVER_TIMING);
        assertEquals(1, values.size(), "one request emitted more than one timing header");
        assertTrue(values.getFirst().matches("admission;dur=[0-9]+,execution;dur=[0-9]+,"
                + "persistence;dur=[0-9]+"),
                "timing exposed a value other than fixed metric names and integer durations");
        assertTrue(values.getFirst().length() < 100);
    }

    private List<String> admissionDiagnostics() {
        final String output = tier.capturedOutput();
        return List.of("UNREADABLE_SUBMISSION", "COMMAND_NOT_SERVED", "UNKNOWN_GENERATION",
                        "RETAINED_GENERATION", "UNBELIEVABLE_REQUEST_START", "NOT_RECORDED")
                .stream().filter(reason -> output.contains("route=submit status=400 refusal=" + reason))
                .toList();
    }

    private DocumentValue.Mapping snapshot(String acknowledgement) {
        final DocumentValue.Mapping document = mapping(acknowledgement);
        final DocumentValue.Whole generation = assertInstanceOf(DocumentValue.Whole.class,
                document.member("agent_event_store_generation").orElseThrow());
        final DocumentValue.Text identifier = assertInstanceOf(DocumentValue.Text.class,
                document.member("agent_operation_identifier").orElseThrow());
        final HttpResponse<String> response = requests.readAsAuthenticatedUser(
                tier.address() + "/bin/slingshot/agent/snapshot?agent_event_store_generation="
                        + generation.value() + "&agent_operation_identifier=" + identifier.value());
        assertEquals(OK, response.statusCode());
        return mapping(response.body());
    }

    private String currentSubmission() {
        final HttpResponse<String> advertised = requests.readAsAuthenticatedUser(
                tier.address() + "/bin/slingshot/agent/capabilities");
        assertEquals(OK, advertised.statusCode());
        final DocumentValue.Mapping capabilities = mapping(advertised.body());
        final DocumentValue.Sequence contracts = assertInstanceOf(DocumentValue.Sequence.class,
                capabilities.member("command_contracts").orElseThrow());
        final DocumentValue.Mapping command = contracts.items().stream()
                .map(value -> assertInstanceOf(DocumentValue.Mapping.class, value))
                .filter(value -> value.member("command_wire_name").orElseThrow()
                        .equals(new DocumentValue.Text("query_paths")))
                .findFirst().orElseThrow();
        final SequencedMap<String, DocumentValue> provenance = new LinkedHashMap<>();
        List.of("format", "transport_contract_digest", "canonical_json_contract_digest")
                .forEach(name -> provenance.put(name, capabilities.member(name).orElseThrow()));
        provenance.put("command_contract", command);
        final DocumentValue.Mapping template = mapping(submission());
        final DocumentValue.Mapping operation = assertInstanceOf(DocumentValue.Mapping.class,
                template.member("operation").orElseThrow());
        final SequencedMap<String, DocumentValue> identity = new LinkedHashMap<>(operation.members());
        identity.put("agent_event_store_generation",
                capabilities.member("agent_event_store_generation").orElseThrow());
        identity.put("agent_operation_identifier", new DocumentValue.Text(
                Digest.of("submission-timing-public-tier".getBytes(StandardCharsets.UTF_8)).rendered()));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>(template.members());
        members.put("operation", new DocumentValue.Mapping(identity));
        members.put("provenance", new DocumentValue.Mapping(provenance));
        members.put("canonical_arguments", new DocumentValue.Text(
                "{\"result_window\":{\"limit\":1,\"mode\":\"initial\",\"offset\":0},\"root_path\":\"/\"}"));
        return new String(assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(new DocumentValue.Mapping(members))).bytes(),
                StandardCharsets.UTF_8);
    }

    private static DocumentValue.Mapping mapping(String document) {
        final AgentContract contract = assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load()).contract();
        return assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class, BoundedDocumentReader.read(
                        document.getBytes(StandardCharsets.UTF_8),
                        BoundedDocumentReader.Bounds.from(contract))).value());
    }

    @Test
    @DisplayName("an authenticated caller outside every permitted group may not start work")
    void anauthenticatedCallerOutsideEveryGroupIsRefused() {
        assertEquals(FORBIDDEN, requests.postAsUnpermittedUser(tier.address() + ROUTE,
                        submission(), "application/json").statusCode(),
                "a caller the operator has not permitted started work, where the only permitted"
                        + " group is " + PERMITTED_GROUP + " and this caller is in none");
    }

    @Test
    @DisplayName("authorization is decided before a body is looked at")
    void authorizationIsDecidedBeforeAbodyIsLookedAt() {
        assertEquals(FORBIDDEN, requests.postAsUnpermittedUser(tier.address() + ROUTE,
                        "this is not a document at all", "application/json").statusCode(),
                "a body was read for a caller who was about to be refused");
    }

    @Test
    @DisplayName("a submission in a media type this route does not take is refused before it is read")
    void asubmissionInAnotherMediaTypeIsRefused() {
        assertEquals(UNSUPPORTED_MEDIA_TYPE, requests.postAsAuthenticatedUser(
                        tier.address() + ROUTE, submission(), "text/plain").statusCode(),
                "a body in a media type this route does not take was read anyway");
    }

    private static String submission() {
        try {
            return Files.readString(REPOSITORY.resolve("core/src/test/resources/fixtures/"
                            + "submit-servlet/a-submission.json"), StandardCharsets.UTF_8)
                    .replaceAll("\"request_start_unix_milliseconds\": [0-9]+",
                            "\"request_start_unix_milliseconds\": " + System.currentTimeMillis());
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    // Neither of the archives a release also builds: a javadoc jar handed to the
                    // platform as a bundle is refused with a 500 that reads like the product
                    // failing to install.
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources")
                            && !String.valueOf(file.getFileName()).contains("javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
