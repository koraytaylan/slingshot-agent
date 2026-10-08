// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import java.util.ArrayList;
import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The two commands about the framework, both of them listings.
 *
 * <p>The listings pass through no gate. Reading what is installed and what activated is the first
 * thing anybody does on an instance they did not build, and it is safe everywhere — a deployment
 * whose bundle state comes from the deployed image still knows perfectly well which ones are
 * running.</p>
 */
public final class FrameworkHandler implements CommandHandler {

    /** The category a framework this side could not ask is reported under. */
    public static final String BUNDLE_INVENTORY_FAILED = "bundle_inventory_failed";

    /** The category a component runtime this side could not ask is reported under. */
    public static final String COMPONENT_INVENTORY_FAILED = "component_inventory_failed";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The five ways a continuation token can be refused, which every paged command declares. */
    public static final List<String> CONTINUATION_CATEGORIES = List.of(
            "continuation_token_malformed", "continuation_token_integrity_invalid",
            "continuation_token_wrong_target", "continuation_token_wrong_query",
            "continuation_token_expired");

    /** Which of the two this handler answers. */
    public enum Kind {
        /** Lists bundles. */
        BUNDLES,
        /** Lists components. */
        COMPONENTS
    }

    private final AgentContract contract;
    private final Kind kind;
    private final BundleInventory inventory;

    /**
     * Holds one handler for one of the two.
     *
     * @param contract the authenticated contract
     * @param kind which of the two commands this handler answers
     * @param inventory what answers questions about the framework
     */
    public FrameworkHandler(AgentContract contract, Kind kind, BundleInventory inventory) {
        this.contract = contract;
        this.kind = kind;
        this.inventory = inventory;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case BUNDLES -> bundles(arguments, context);
            case COMPONENTS -> components(arguments, context);
        };
    }

    private Answer bundles(DocumentValue.Mapping arguments, CallerContext context) {
        final ListBundlesCommand.Outcome asked = ListBundlesCommand.of(arguments, contract);
        if (asked instanceof final ListBundlesCommand.Refused refused) {
            return new Failed(BUNDLE_INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final ListBundlesCommand command = ((ListBundlesCommand.Held) asked).command();
        final PagingSupport.Preparation prepared = PagingSupport.prepare(command.window(),
                ListBundlesCommand.WIRE_NAME, arguments, context, contract);
        if (prepared instanceof final PagingSupport.WindowRefused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final BundleInventory.Outcome found =
                inventory.bundles(command.prefix(), command.states());
        if (found instanceof final BundleInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<BundleInventory.BundleEntry> entries =
                ((BundleInventory.Bundles) found).entries();
        if (entries.size() > context.discovery().limit()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, entries.size() + " bundles is more than the "
                        + context.discovery().limit() + " this caller may examine");
        }
        final PagingSupport.Outcome<BundleInventory.BundleEntry> page = PagingSupport.page(entries,
                (PagingSupport.Ready) prepared, ListBundlesCommand.WIRE_NAME, context, contract);
        if (page instanceof final PagingSupport.Refused<BundleInventory.BundleEntry> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<BundleInventory.BundleEntry> accepted =
                ((PagingSupport.Accepted<BundleInventory.BundleEntry>) page).page();
        return new Produced(FrameworkResults.bundlesOf(accepted.rows(),
                accepted.continuationToken()));
    }

    private Answer components(DocumentValue.Mapping arguments, CallerContext context) {
        final ListComponentsCommand.Outcome asked = ListComponentsCommand.of(arguments, contract);
        if (asked instanceof final ListComponentsCommand.Refused refused) {
            return new Failed(COMPONENT_INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final ListComponentsCommand command = ((ListComponentsCommand.Held) asked).command();
        final PagingSupport.Preparation prepared = PagingSupport.prepare(command.window(),
                ListComponentsCommand.WIRE_NAME, arguments, context, contract);
        if (prepared instanceof final PagingSupport.WindowRefused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final BundleInventory.Outcome found =
                inventory.components(command.prefix(), command.states());
        if (found instanceof final BundleInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<BundleInventory.ComponentEntry> entries =
                ((BundleInventory.Components) found).entries();
        if (entries.size() > context.discovery().limit()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, entries.size() + " components is more than"
                        + " the " + context.discovery().limit() + " this caller may examine");
        }
        final PagingSupport.Outcome<BundleInventory.ComponentEntry> page = PagingSupport.page(entries,
                (PagingSupport.Ready) prepared, ListComponentsCommand.WIRE_NAME, context, contract);
        if (page instanceof final PagingSupport.Refused<BundleInventory.ComponentEntry> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<BundleInventory.ComponentEntry> accepted =
                ((PagingSupport.Accepted<BundleInventory.ComponentEntry>) page).page();
        return new Produced(FrameworkResults.componentsOf(accepted.rows(),
                accepted.continuationToken()));
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

    @Override
    public List<String> categories() {
        return switch (kind) {
            case BUNDLES -> listingCategories(BUNDLE_INVENTORY_FAILED);
            case COMPONENTS -> listingCategories(COMPONENT_INVENTORY_FAILED);
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
}
