// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import java.util.ArrayList;
import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The three commands about the framework: two listings and one control.
 *
 * <p>The listings pass through no gate. Reading what is installed and what activated is the first
 * thing anybody does on an instance they did not build, and it is safe everywhere — a deployment
 * that will not let a bundle be stopped still knows perfectly well which ones are running.</p>
 *
 * <p>The control passes through the gate before its argument is read, so a caller on a deployment
 * whose bundle state comes from the deployed image is told that rather than told their transition
 * was misspelled.</p>
 */
public final class FrameworkHandler implements CommandHandler {

    /** The category a framework this side could not ask is reported under. */
    public static final String BUNDLE_INVENTORY_FAILED = "bundle_inventory_failed";

    /** The category a component runtime this side could not ask is reported under. */
    public static final String COMPONENT_INVENTORY_FAILED = "component_inventory_failed";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category a bundle nothing is called is refused under. */
    public static final String BUNDLE_NOT_FOUND = "bundle_not_found";

    /** The category the platform refusing a control is reported under. */
    public static final String CONTROL_REJECTED = "platform_control_rejected";

    /**
     * The category a transition the framework itself would not make is reported under.
     *
     * <p>Told apart from the platform refusing the control, because the two send an operator to
     * different places. The platform refusing means this deployment does not do that at all; the
     * framework refusing means this bundle would not go — a dependency is missing, or something it
     * needs is already gone.</p>
     */
    public static final String TRANSITION_REFUSED = "bundle_transition_refused";

    /** The five ways a continuation token can be refused, which every paged command declares. */
    public static final List<String> CONTINUATION_CATEGORIES = List.of(
            "continuation_token_malformed", "continuation_token_integrity_invalid",
            "continuation_token_wrong_target", "continuation_token_wrong_query",
            "continuation_token_expired");

    /** Which of the three this handler answers. */
    public enum Kind {
        /** Lists bundles. */
        BUNDLES,
        /** Lists components. */
        COMPONENTS,
        /** Puts one bundle through a transition. */
        TRANSITION
    }

    private final AgentContract contract;
    private final Kind kind;
    private final BundleInventory inventory;
    private final PlatformControl control;

    /**
     * Holds one handler for one of the three.
     *
     * @param contract the authenticated contract
     * @param kind which of the three commands this handler answers
     * @param inventory what answers questions about the framework and changes bundle state
     * @param control what this deployment permits, asked before the control proceeds
     */
    public FrameworkHandler(AgentContract contract, Kind kind, BundleInventory inventory,
                            PlatformControl control) {
        this.contract = contract;
        this.kind = kind;
        this.inventory = inventory;
        this.control = control;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case BUNDLES -> bundles(arguments, context);
            case COMPONENTS -> components(arguments, context);
            case TRANSITION -> transitioned(arguments);
        };
    }

    private Answer bundles(DocumentValue.Mapping arguments, CallerContext context) {
        final ListBundlesCommand.Outcome asked = ListBundlesCommand.of(arguments, contract);
        if (asked instanceof final ListBundlesCommand.Refused refused) {
            return new Failed(BUNDLE_INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final ListBundlesCommand command = ((ListBundlesCommand.Held) asked).command();
        final BundleInventory.Outcome found =
                inventory.bundles(command.prefix(), command.states());
        if (found instanceof final BundleInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<BundleInventory.BundleEntry> entries =
                ((BundleInventory.Bundles) found).entries();
        return entries.size() > context.discovery().limit()
                ? new Failed(DISCOVERY_BUDGET_EXCEEDED, entries.size() + " bundles is more than the "
                        + context.discovery().limit() + " this caller may examine")
                : new Produced(FrameworkResults.bundlesOf(pageOf(entries, command.window()),
                        FrameworkResults.NO_MORE_PAGES));
    }

    private Answer components(DocumentValue.Mapping arguments, CallerContext context) {
        final ListComponentsCommand.Outcome asked = ListComponentsCommand.of(arguments, contract);
        if (asked instanceof final ListComponentsCommand.Refused refused) {
            return new Failed(COMPONENT_INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final ListComponentsCommand command = ((ListComponentsCommand.Held) asked).command();
        final BundleInventory.Outcome found =
                inventory.components(command.prefix(), command.states());
        if (found instanceof final BundleInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<BundleInventory.ComponentEntry> entries =
                ((BundleInventory.Components) found).entries();
        return entries.size() > context.discovery().limit()
                ? new Failed(DISCOVERY_BUDGET_EXCEEDED, entries.size() + " components is more than"
                        + " the " + context.discovery().limit() + " this caller may examine")
                : new Produced(FrameworkResults.componentsOf(pageOf(entries, command.window()),
                        FrameworkResults.NO_MORE_PAGES));
    }

    /**
     * The window's worth of entries.
     *
     * @param entries every entry the framework holds, in its own order
     * @param window which page is wanted
     * @param <Entry> what kind of entry this is
     * @return the entries that page carries
     */
    public static <Entry> List<Entry> pageOf(List<Entry> entries, ResultWindow window) {
        if (!(window instanceof final ResultWindow.Initial initial)) {
            return entries;
        }
        return entries.stream().skip(initial.offset()).limit(initial.limit()).toList();
    }

    private Answer transitioned(DocumentValue.Mapping arguments) {
        final PlatformControl.Verdict verdict =
                control.permits(ControlCapability.BUNDLE_LIFECYCLE);
        if (verdict instanceof final PlatformControl.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final SetBundleStateCommand.Outcome asked = SetBundleStateCommand.of(arguments, contract);
        if (asked instanceof final SetBundleStateCommand.Refused refused) {
            return new Failed(BUNDLE_NOT_FOUND, refused.refusal() + ": " + refused.detail());
        }
        final SetBundleStateCommand command = ((SetBundleStateCommand.Held) asked).command();
        final BundleInventory.Outcome moved =
                inventory.transition(command.symbolicName(), command.transition());
        return moved instanceof final BundleInventory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(FrameworkResults.transitionOf(command.symbolicName(),
                        ((BundleInventory.Transitioned) moved).observed()));
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case BUNDLES -> listingCategories(BUNDLE_INVENTORY_FAILED);
            case COMPONENTS -> listingCategories(COMPONENT_INVENTORY_FAILED);
            case TRANSITION -> transitionCategories();
        };
    }

    /**
     * Everything one framework listing can fail with.
     *
     * @param inventoryFailed the category that listing reports an unaskable framework under
     * @return the categories
     */
    public static List<String> listingCategories(String inventoryFailed) {
        final List<String> categories = new ArrayList<>(List.of(DISCOVERY_BUDGET_EXCEEDED));
        categories.addAll(CONTINUATION_CATEGORIES);
        categories.add(inventoryFailed);
        return List.copyOf(categories);
    }

    /**
     * Everything one bundle transition can fail with.
     *
     * @return the categories
     */
    public static List<String> transitionCategories() {
        return List.of(BUNDLE_NOT_FOUND, TRANSITION_REFUSED, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }
}
