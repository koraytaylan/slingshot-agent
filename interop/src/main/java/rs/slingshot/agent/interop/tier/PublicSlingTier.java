// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import rs.slingshot.agent.interop.harness.ContainerHandle;
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * The tier that needs nothing licensed.
 *
 * <p>Everything in the Sling-only bundle resolves against a plain Apache Sling runtime, so the whole
 * protocol surface is proved on an image anybody can pull, on any machine and in continuous
 * integration. That is what the two-bundle split is for, and it is why this tier installs only what
 * it can resolve and asserts the Adobe bundle is <em>absent</em> rather than installed and
 * unresolved: a failure here is never to be mistaken for a missing Adobe interface.</p>
 *
 * <p>The image is pinned by digest and never pulled. An absent image and one whose digest differs
 * are two refusals that name the preparation command, because a tier that pulled at run time would
 * make the gate's claim to fetch nothing false.</p>
 */
public final class PublicSlingTier implements InteropTier {

    /** The tier's own letter, as the tier inventory gives it. */
    public static final String NAME = "a";

    /** The port a Sling runtime answers on inside its own container. */
    public static final int SLING_PORT = 8080;

    /** Where the platform reports what it has installed. */
    private static final String BUNDLE_STATE_PATH = "/system/console/bundles.json";

    /** Where a bundle is handed to the platform to install. */
    private static final String BUNDLE_INSTALL_PATH = "/system/console/bundles";

    /** The bundle this tier installs, which is the one that resolves without Adobe. */
    public static final String CORE_BUNDLE = "rs.slingshot.agent.core";

    /** The bundle this tier must not have, so a failure here is never mistaken for a missing API. */
    public static final String ADOBE_BUNDLE = "rs.slingshot.agent.aem";

    /** The group this agent permits, which an author has and a plain Sling does not. */
    private static final String PERMITTED_GROUP = "administrators";

    /** Where the platform's own user manager makes a group. */
    private static final String GROUP_CREATE_PATH = "/system/userManager/group.create.html";

    /** Where the platform's own user manager answers for that group. */
    private static final String GROUP_PATH =
            "/system/userManager/group/" + PERMITTED_GROUP + ".json";

    /** Where that group's membership is changed. */
    private static final String GROUP_MEMBERSHIP_PATH =
            "/system/userManager/group/" + PERMITTED_GROUP + ".update.html";

    /** Where the platform's own user manager makes a user. */
    private static final String USER_CREATE_PATH = "/system/userManager/user.create.html";

    /** Where the user manager answers for the caller nobody has permitted. */
    private static final String UNPERMITTED_USER_PATH =
            "/system/userManager/user/" + TierRequests.UNPERMITTED_USER + ".json";

    /** The caller this tier authenticates as, by the path the user manager knows them at. */
    private static final String AUTHENTICATED_CALLER_PATH = "/system/userManager/user/admin";

    private final ContainerHarness harness;
    private final ContainerHandle handle;
    private final TierRequests requests;

    private PublicSlingTier(ContainerHarness harness, ContainerHandle handle,
                            TierRequests requests) {
        this.harness = harness;
        this.handle = handle;
        this.requests = requests;
    }

    /**
     * Starts the pinned public image, installs what this tier can resolve, and waits for it.
     *
     * @param root the repository root
     * @param image the pinned image, at the digest it was prepared at
     * @param bundle the built Sling-only bundle to install
     * @return the running tier, or the one reason there is none
     */
    public static Outcome start(Path root, String image, Path bundle) {
        final ContainerHarness harness = ContainerHarness.at(root);
        if (!harness.holds(image)) {
            return new Refused(Failure.INPUT_ABSENT, image
                    + " is not held by this engine; run scripts/prepare_interop_images");
        }
        return startWith(harness, TierRequests.open(), image, bundle);
    }

    /**
     * Starts the pinned image with a client already built, and installs what this tier can resolve.
     *
     * @param harness the wrapper the container is started through
     * @param client what every request to the running instance is made with
     * @param image the pinned image, at the digest it was prepared at
     * @param bundle the built Sling-only bundle to install
     * @return the running tier, or the one reason there is none
     */
    private static Outcome startWith(ContainerHarness harness, TierRequests requests, String image,
                                     Path bundle) {
        // Ready means a request is served the way a caller's would be, not merely that something
        // is answering. The console answers while the platform is still assembling itself, and the
        // root answers with a redirect the moment the web layer is up - both of them before the
        // servlet resolver exists, which is the service every route here needs. A tier that waited
        // on either would install a bundle into an instance that then answered "cannot service
        // requests" to the first thing the scenario asked, which is a failure about the wait rather
        // than about the product. So the condition is a rendered resource: it needs the resolver
        // and the repository, and nothing answers it until both are there.
        final ContainerHarness.Outcome started = harness.start(image, SLING_PORT, List.of(),
                starting -> requests.respondsBelow(starting.address() + BUNDLE_STATE_PATH,
                                BAD_REQUEST)
                        && requests.respondsBelow(starting.address() + RENDERED_ROOT,
                                SERVICE_UNAVAILABLE)
                        && requests.submitRespondsBelow(starting.address() + "/", CHANGES_NOTHING,
                                SERVICE_UNAVAILABLE));
        if (started instanceof final ContainerHarness.Refused refused) {
            return new Refused(Failure.RUNTIME_NOT_READY,
                    refused.failure() + ": " + refused.detail());
        }
        final ContainerHandle running = ((ContainerHarness.Started) started).handle();
        final PublicSlingTier tier = new PublicSlingTier(harness, running, requests);
        final Optional<String> installed = tier.install(bundle);
        if (installed.isPresent()) {
            tier.stop();
            return new Refused(Failure.NOT_INSTALLED, installed.get());
        }
        final Optional<String> permitted = tier.permitTheCaller();
        if (permitted.isPresent()) {
            tier.stop();
            return new Refused(Failure.NOT_INSTALLED, permitted.get());
        }
        return new Running(tier);
    }

    /**
     * Puts the caller this tier authenticates as into the group this agent permits.
     *
     * <p>The agent permits one group and refuses a permitted group the instance does not have,
     * naming it. That is the right refusal, and it is why nothing can be submitted here until this
     * has run: the deployment this product is built for has that group, and a plain Sling has no
     * group by that name at all. Making it is what turns this runtime into the deployment. Without
     * it every submission is refused before its body is read, and every scenario that means to
     * prove what happens to a submission would quietly prove what happens to a caller instead.</p>
     *
     * @return nothing where the caller is permitted, or what was observed where they are not
     */
    private Optional<String> permitTheCaller() {
        // Asked for first, because a tier that is brought up twice against storage it keeps would
        // otherwise be refused for making a group that is already there, which is a failure about
        // the second start rather than about the product.
        if (requests.readAsAuthenticatedUser(address() + GROUP_PATH).statusCode() >= BAD_REQUEST) {
            final HttpResponse<String> made = requests.submitWithReferrer(
                    address() + GROUP_CREATE_PATH, List.of(":name", PERMITTED_GROUP),
                    address() + "/");
            if (made.statusCode() >= BAD_REQUEST) {
                return Optional.of("the group " + PERMITTED_GROUP + " could not be made on this"
                        + " instance, and every submission is refused until it exists: "
                        + made.statusCode() + " " + said(made));
            }
        }
        final HttpResponse<String> joined = requests.submitWithReferrer(
                address() + GROUP_MEMBERSHIP_PATH,
                List.of(":member", AUTHENTICATED_CALLER_PATH), address() + "/");
        if (joined.statusCode() >= BAD_REQUEST) {
            return Optional.of("the caller was not put into " + PERMITTED_GROUP + ", and a caller"
                    + " in none of the permitted groups is refused before a body is read: "
                    + joined.statusCode() + " " + said(joined));
        }
        return makeTheCallerNobodyPermits();
    }

    /**
     * Makes a caller the platform authenticates and no operator has permitted.
     *
     * <p>Somebody has to be outside every permitted group for the scenarios about being outside one
     * to be about anything. Before the group above existed that was every caller by accident, which
     * is a proof that stops the moment the accident does — so the tier makes a caller who is
     * deliberately outside and leaves them there.</p>
     *
     * @return nothing where that caller exists, or what was observed where they do not
     */
    private Optional<String> makeTheCallerNobodyPermits() {
        if (requests.readAsAuthenticatedUser(address() + UNPERMITTED_USER_PATH).statusCode()
                < BAD_REQUEST) {
            return Optional.empty();
        }
        final HttpResponse<String> made = requests.submitWithReferrer(address() + USER_CREATE_PATH,
                // The name carries the prefix the posting servlet reads and the password does
                // not, which is the user manager's own spelling rather than a choice made here.
                List.of(":name", TierRequests.UNPERMITTED_USER,
                        "pwd", TierRequests.UNPERMITTED_PASSWORD,
                        "pwdConfirm", TierRequests.UNPERMITTED_PASSWORD), address() + "/");
        if (made.statusCode() >= BAD_REQUEST) {
            return Optional.of("the caller nobody permits could not be made on this instance, and"
                    + " what is outside every group would then be nobody at all: "
                    + made.statusCode() + " " + said(made));
        }
        return Optional.empty();
    }

    /** How many times the bundle state is asked for before the install is called a failure. */
    private static final int INSTALL_ATTEMPTS = 60;

    /** How long between those asks. */
    private static final int INSTALL_POLL_MILLISECONDS = 1000;

    /**
     * How many asks a freshly handed-over bundle is given before being installed and unresolved is
     * called a failure rather than a moment. The platform reports a bundle as installed between
     * receiving it and resolving it, and reading that moment as the outcome would report a
     * resolution failure for a bundle that resolves a fraction of a second later.
     */
    private static final int SETTLING_ATTEMPTS = 20;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String address() {
        return handle.address();
    }

    @Override
    public HttpResponse<String> readAsAuthenticatedUser(String path) {
        return requests.readAsAuthenticatedUser(address() + path);
    }

    @Override
    public HttpResponse<String> readAsNobody(String path) {
        return requests.readAsNobody(address() + path);
    }

    /**
     * Hands form fields to the instance as the user it authenticated.
     *
     * <p>This is how a scenario puts something into the repository rather than reading it: the
     * platform's own posting servlet is what a deployment uses, so it is what a scenario uses too.
     * </p>
     *
     * @param path where to hand them over
     * @param fields the fields, as name and value in turn
     * @return what the instance answered
     */
    public HttpResponse<String> submit(String path, List<String> fields) {
        return requests.submit(address() + path, fields);
    }

    /**
     * Hands a file to the instance as the user it authenticated.
     *
     * @param path where to hand it over
     * @param fileField the field the file arrives under, which names the node it becomes
     * @param file the file itself
     * @return what the instance answered
     */
    public HttpResponse<String> upload(String path, String fileField, Path file) {
        return requests.upload(address() + path, List.of(), fileField, file);
    }

    @Override
    public Optional<String> bundleState(String symbolicName) {
        final HttpResponse<String> reported = readAsAuthenticatedUser(BUNDLE_STATE_PATH);
        return stateOf(reported.body(), symbolicName);
    }

    @Override
    public void stop() {
        harness.stop(handle);
    }

    /**
     * What the running instance wrote while it started, bounded.
     *
     * @return the captured output
     */
    @Override
    public String capturedOutput() {
        return harness.capturedOutput(handle);
    }

    /** What the platform says about a bundle, in the three answers this wait distinguishes. */
    public enum Reported {
        /** Installed, resolved, and started, which is what the install was waiting for. */
        ACTIVE,
        /** Received and not resolved, which is a moment at first and a cause after that. */
        INSTALLED,
        /** Anything else, including the platform holding no such bundle at all. */
        OTHERWISE
    }

    /**
     * What the platform currently says about one bundle, as one of three answers.
     *
     * @param symbolicName the bundle's own name
     * @return the reported state
     */
    public Reported reportedState(String symbolicName) {
        return bundleState(symbolicName)
                .map(state -> switch (state) {
                    case "Active" -> Reported.ACTIVE;
                    case "Installed" -> Reported.INSTALLED;
                    default -> Reported.OTHERWISE;
                })
                .orElse(Reported.OTHERWISE);
    }

    /**
     * The state the platform reports for one bundle, read out of its own report.
     *
     * @param report what the platform answered
     * @param symbolicName the bundle's own name
     * @return the state, or nothing where the platform holds no such bundle
     */
    public static Optional<String> stateOf(String report, String symbolicName) {
        final int named = report.indexOf("\"symbolicName\":\"" + symbolicName + "\"");
        if (named < 0) {
            return Optional.empty();
        }
        // The state sits before the symbolic name in each entry, so the one that belongs to this
        // bundle is the last one written before it - not the next one after, which belongs to
        // whichever bundle the platform listed next.
        final int state = report.lastIndexOf("\"state\":\"", named);
        if (state < 0) {
            return Optional.empty();
        }
        final int from = state + "\"state\":\"".length();
        return Optional.of(report.substring(from, report.indexOf('"', from)));
    }

    private Optional<String> install(Path bundle) {
        final HttpResponse<String> handed = requests.upload(address() + BUNDLE_INSTALL_PATH,
                List.of("action", "install", "bundlestart", "start"), BUNDLE_FIELD, bundle);
        if (handed.statusCode() >= BAD_REQUEST) {
            return Optional.of("the platform refused the bundle with " + handed.statusCode());
        }
        final Optional<String> active = awaitActive();
        return active.isPresent() ? active : awaitRegistered();
    }

    /** How much of a platform's own refusal travels with ours. */
    private static final int KEPT_CHARACTERS = 400;

    /**
     * What the platform said, rather than only the number it said it with.
     *
     * <p>A status alone names the shape of a refusal and not its cause, and these run where the
     * instance is gone by the time anybody reads the failure.</p>
     *
     * @param answer what the platform answered
     * @return the readable part of it
     */
    private static String said(HttpResponse<String> answer) {
        final String flattened = answer.body().replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ").strip();
        return flattened.length() <= KEPT_CHARACTERS ? flattened
                : flattened.substring(0, KEPT_CHARACTERS) + "...";
    }

    /** The field the platform console takes a bundle under. */
    private static final String BUNDLE_FIELD = "bundlefile";

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    private Optional<String> awaitActive() {
        return IntStream.range(0, INSTALL_ATTEMPTS)
                .mapToObj(attempt -> settledState(attempt))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(Optional.of(CORE_BUNDLE + " did not reach the active state"));
    }

    private Optional<Optional<String>> settledState(int attempt) {
        if (attempt > 0) {
            pause();
        }
        return settlement(attempt, reportedState(CORE_BUNDLE));
    }

    /**
     * What one reported state means, given how many times it has been asked for already.
     *
     * <p>This is the whole of what the wait decides, kept apart from the waiting itself so that
     * what it decides can be proved without a running instance. Three answers: the bundle is
     * active and the install is done; it has been installed and unresolved for longer than the
     * platform takes to resolve one, which is a failure with a cause; or nothing yet, and the
     * caller asks again.</p>
     *
     * <p>Installed and unresolved is reported as its own thing rather than as a timeout, because it
     * means one of the bundle's imported packages is not provided by this runtime — which is
     * exactly the failure this tier exists to catch, and waiting sixty seconds to call it a timeout
     * would hide it.</p>
     *
     * @param attempt which ask this is, counted from zero
     * @param state what the platform reported, which is {@link Reported#OTHERWISE} where it holds
     *     no such bundle at all
     * @return the outcome where there is one, and nothing while the answer is still to come
     */
    public static Optional<Optional<String>> settlement(int attempt, Reported state) {
        if (state == Reported.ACTIVE) {
            return Optional.of(Optional.empty());
        }
        if (attempt >= SETTLING_ATTEMPTS && state == Reported.INSTALLED) {
            return Optional.of(Optional.of(CORE_BUNDLE + " is installed and unresolved, which means"
                    + " one of its imported packages is not provided by this runtime"));
        }
        return Optional.empty();
    }

    /**
     * Waits until a route this bundle serves answers as itself.
     *
     * <p>Active is the bundle's state, not its components'. The platform reports a bundle active
     * the moment it is started, and the declarative-services runtime registers that bundle's
     * servlets some time after — so between the two there is a window in which every route this
     * product owns is genuinely absent, and the servlet resolver answers a caller the way it
     * answers a caller asking for anything else that is not there. A scenario that began in that
     * window would read that answer as the product's own, which is the worst kind of failure a
     * harness can produce: it looks exactly like the route regression it is not, and it comes and
     * goes with how loaded the machine is.</p>
     *
     * <p>So the wait ends on the one answer only a registered route gives. An unauthenticated
     * caller asking a route this bundle owns is refused for being unauthenticated; the same caller
     * asking a path nothing serves is told there is nothing there. The first is proof of
     * registration, and no unregistered instance can produce it.</p>
     *
     * @return nothing where a route answered as itself, or what to say about an instance whose
     *     bundle is active and whose components never registered
     */
    private Optional<String> awaitRegistered() {
        return IntStream.range(0, INSTALL_ATTEMPTS)
                .mapToObj(this::registrationAt)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(Optional.of(CORE_BUNDLE + " is active and " + A_ROUTE_THIS_BUNDLE_OWNS
                        + " still answers as a path nothing serves, so its components never"
                        + " registered"));
    }

    private Optional<Optional<String>> registrationAt(int attempt) {
        if (attempt > 0) {
            pause();
        }
        return registration(
                requests.readAsNobody(address() + A_ROUTE_THIS_BUNDLE_OWNS).statusCode());
    }

    /**
     * What one answer to an unauthenticated caller says about whether the route exists.
     *
     * <p>Kept apart from the waiting so that what it decides can be proved without a running
     * instance, the same way the bundle state's settlement is.</p>
     *
     * @param status what the instance answered an unauthenticated caller with
     * @return the outcome where the route has proved it exists, and nothing while it has not
     */
    public static Optional<Optional<String>> registration(int status) {
        return status == UNAUTHENTICATED ? Optional.of(Optional.empty()) : Optional.empty();
    }

    /**
     * The route the registration wait asks for.
     *
     * <p>Any route this bundle owns would do. This one is the route every tier already reads, so a
     * wait that ended on it has proved the thing the scenarios go on to use.</p>
     */
    private static final String A_ROUTE_THIS_BUNDLE_OWNS = "/bin/slingshot/agent/capabilities";

    /** What a route this bundle owns answers a caller who presented no identity. */
    private static final int UNAUTHENTICATED = 401;

    private static void pause() {
        try {
            Thread.sleep(INSTALL_POLL_MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }



    /** The status a runtime answers with while it is still starting. */
    private static final int SERVICE_UNAVAILABLE = 500;

    /** A resource whose rendering needs the servlet resolver and the repository both. */
    private static final String RENDERED_ROOT = "/.json";

    /**
     * The platform's own way of being asked to do nothing.
     *
     * <p>Reading is served before writing is, by enough of a margin for a condition that only read
     * to pass in the moment before a write would have been refused. This asks the posting servlet
     * for the one operation that changes nothing, so the wait ends when both halves of what this
     * tier does are actually being served - and the instance is left exactly as it was.</p>
     */
    private static final List<String> CHANGES_NOTHING =
            List.of(":operation", "nop", ":nopstatus", "200");


}
