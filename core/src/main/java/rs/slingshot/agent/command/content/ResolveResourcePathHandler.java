// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one request address resolves to on this instance, answered by the platform's own resolver.
 *
 * <p>The platform does the resolving. This reports what it did rather than re-deriving the answer
 * from the mapping configuration: a re-derivation would be plausible and could differ from what the
 * instance really does, which is exactly the discrepancy an operator is chasing when they ask.</p>
 *
 * <p>The parts of the request the platform did not use to resolve — the selectors, the extension,
 * the suffix — are read off the resolution itself for the same reason. Sling records what it
 * consumed while resolving, and reading that back is a report; splitting the address on dots here
 * would be a second parser that agrees with the platform until the day it does not.</p>
 */
public final class ResolveResourcePathHandler implements CommandHandler {

    /** The category a translation that produced nothing is reported under. */
    public static final String RESOLUTION_FAILED = "resolution_failed";

    /** The category a translation that took more steps than allowed is refused under. */
    public static final String RESOLUTION_BUDGET_EXCEEDED = "resolution_budget_exceeded";

    /** The category a request address the mapping rules will not accept is refused under. */
    public static final String REQUEST_ADDRESS_REJECTED = "request_address_rejected";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public ResolveResourcePathHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ResolveResourcePathCommand.Outcome asked =
                ResolveResourcePathCommand.of(arguments, contract);
        if (asked instanceof final ResolveResourcePathCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        return resolved(((ResolveResourcePathCommand.Held) asked).command(), resolver);
    }

    /**
     * Which declared category one argument refusal is reported under.
     *
     * <p>A request address the rules reject is its own category, because it is a malformed question
     * rather than a correct answer of no.</p>
     *
     * @param refusal why the argument was refused
     * @return the category
     */
    public static String categoryFor(ResolveResourcePathCommand.Refusal refusal) {
        return refusal == ResolveResourcePathCommand.Refusal.REQUEST_ADDRESS_REJECTED
                ? REQUEST_ADDRESS_REJECTED : ARGUMENT_REJECTED;
    }

    private Answer resolved(ResolveResourcePathCommand command, ResourceResolver resolver) {
        // Resolving always produces a resource: an address nothing is at produces one standing for
        // nothing, whose path is still the address. So a resolution that produced nothing is a
        // blank path rather than an absence.
        final Resource held = resolver.resolve(command.requestAddress());
        final String path = held.getPath();
        if (path.isBlank()) {
            return new Failed(RESOLUTION_FAILED, command.requestAddress() + " resolved to nothing."
                    + " That is a correct answer of no rather than a malformed question: the"
                    + " address is well formed and no rule produced anything for it.");
        }
        final List<String> trace = traced(command, path);
        if (trace.size() > contract.value(ContractLimit.MAXIMUM_RESOLUTION_TRACE_ENTRIES)) {
            return new Failed(RESOLUTION_BUDGET_EXCEEDED, "this resolution went through more than"
                    + " the " + contract.value(ContractLimit.MAXIMUM_RESOLUTION_TRACE_ENTRIES)
                    + " rules it may report");
        }
        final RequestParts parts = RequestParts.of(leftOver(held));
        return new Produced(ResolveResourcePathResult.documentOf(
                new ResolveResourcePathResult.Resolution(command.requestAddress(), path,
                        typeOf(held), parts.selectors(), parts.extension(), parts.suffix(),
                        trace)));
    }

    /**
     * The rules one resolution went through, where the caller asked to see them.
     *
     * <p>The platform does not publish its own steps, so what is reported is the two ends of the
     * resolution: the address as it arrived and the path it became. That is a trace of one step,
     * and it is honest — an operator reading it learns what changed rather than being handed a
     * reconstruction of how, which this side would have to invent.</p>
     *
     * @param command what was asked
     * @param resolved where the address resolved to
     * @return the rules, which is empty where none were asked for or nothing changed
     */
    private static List<String> traced(ResolveResourcePathCommand command, String resolved) {
        if (command.trace() == TraceDisclosure.OMITTED
                || command.requestAddress().equals(resolved)) {
            return List.of();
        }
        return List.of(resolved);
    }

    private static String typeOf(Resource held) {
        // Every resource has a type, including the one standing for an address nothing is at: its
        // type is the platform's own name for nothing. So there is no absent case to answer here.
        return held.getResourceType();
    }

    /**
     * The parts of the request the platform did not use to resolve.
     *
     * <p>Sling records them as one string beginning at the first dot — {@code .sel.html/suffix} —
     * and {@link RequestParts} takes that record apart.</p>
     *
     * @param held the resolved resource
     * @return what was left over, which is empty where the whole address resolved
     */
    private static String leftOver(Resource held) {
        final String remainder = held.getResourceMetadata().getResolutionPathInfo();
        return remainder == null ? "" : remainder;
    }


    @Override
    public List<String> categories() {
        return List.of(REQUEST_ADDRESS_REJECTED, RESOLUTION_BUDGET_EXCEEDED, RESOLUTION_FAILED);
    }
}
