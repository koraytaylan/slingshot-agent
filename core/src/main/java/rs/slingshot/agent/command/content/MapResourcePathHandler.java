// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one repository path publishes as, answered by the platform's own resolver.
 *
 * <p>Mapping is not the mirror of resolving and this handler is separate for that reason. There is
 * no request to happen under, the answer is an address rather than a path, and a caller may ask
 * what a path looks like from a particular host. Sharing one handler between the two would mean one
 * argument type describing two documents, which is the shape the client's own schemas refuse.</p>
 */
public final class MapResourcePathHandler implements CommandHandler {

    /** The category a translation that produced nothing is reported under. */
    public static final String RESOLUTION_FAILED = "resolution_failed";

    /** The category a translation that took more steps than allowed is refused under. */
    public static final String RESOLUTION_BUDGET_EXCEEDED = "resolution_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public MapResourcePathHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final MapResourcePathCommand.Outcome asked =
                MapResourcePathCommand.of(arguments, contract);
        if (asked instanceof final MapResourcePathCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return mapped(((MapResourcePathCommand.Held) asked).command(), resolver);
    }

    private Answer mapped(MapResourcePathCommand command, ResourceResolver resolver) {
        final String produced = resolver.map(command.repositoryPath());
        if (produced.isBlank()) {
            return new Failed(RESOLUTION_FAILED, command.repositoryPath() + " maps to nothing."
                    + " That is a correct answer of no rather than a malformed question: the path"
                    + " is well formed and no rule produced anything for it.");
        }
        final List<String> trace = traced(command, produced);
        if (trace.size() > contract.value(ContractLimit.MAXIMUM_RESOLUTION_TRACE_ENTRIES)) {
            return new Failed(RESOLUTION_BUDGET_EXCEEDED, "this mapping went through more than the "
                    + contract.value(ContractLimit.MAXIMUM_RESOLUTION_TRACE_ENTRIES)
                    + " rules it may report");
        }
        return new Produced(MapResourcePathResult.documentOf(command.repositoryPath(), produced,
                trace));
    }

    /**
     * The rules one mapping went through, where the caller asked to see them.
     *
     * <p>A trace's entries are repository paths, so what can honestly go in one is the path the
     * mapping started from. The address it produced is not a repository path and is already the
     * answer; repeating it here as a rule would be describing the result as its own cause.</p>
     *
     * @param command what was asked
     * @param produced what the platform produced
     * @return the rules, which is empty where none were asked for or nothing changed
     */
    private static List<String> traced(MapResourcePathCommand command, String produced) {
        if (command.trace() == TraceDisclosure.OMITTED
                || command.repositoryPath().equals(produced)) {
            return List.of();
        }
        return List.of(command.repositoryPath());
    }

    @Override
    public List<String> categories() {
        return List.of(RESOLUTION_BUDGET_EXCEEDED, RESOLUTION_FAILED);
    }
}
