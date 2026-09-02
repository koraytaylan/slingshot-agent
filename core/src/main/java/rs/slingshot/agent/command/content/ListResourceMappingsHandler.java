// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The resolution rules as the platform holds them, in the order it applies them.
 *
 * <p>Application order, not declaration order. The two differ, and the difference is the whole
 * reason somebody is reading this listing: they have an address that resolved unexpectedly and they
 * want to know which rule got to it first. A listing in declaration order would look right and lead
 * them to the wrong rule.</p>
 *
 * <p>An inventory that cannot be read is a refusal rather than an empty list. An empty list of
 * mapping rules is a perfectly sensible thing for a deployment to have, so answering one when the
 * inventory could not be read tells a reader something false about their instance in a way they
 * have no reason to doubt.</p>
 */
public final class ListResourceMappingsHandler implements CommandHandler {

    /** Where the platform keeps its resource mapping configuration. */
    public static final String MAPPING_ROOT = "/etc/map";

    /** The property a mapping entry records an inward rewrite in. */
    public static final String INTERNAL_REDIRECT_PROPERTY = "sling:internalRedirect";

    /** The property a mapping entry records an outward redirect in. */
    public static final String REDIRECT_PROPERTY = "sling:redirect";

    /** The property a mapping entry records a short name in. */
    public static final String ALIAS_PROPERTY = "sling:alias";

    /** The property a redirecting entry records the status it answers with in. */
    public static final String STATUS_PROPERTY = "sling:status";

    /** The property one mapping entry keeps a match pattern in. */
    public static final String MATCH_PROPERTY = "sling:match";

    /** The category an inventory that could not be read is refused under. */
    public static final String INVENTORY_FAILED = "mapping_inventory_failed";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public ListResourceMappingsHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ListResourceMappingsCommand.Outcome asked =
                ListResourceMappingsCommand.of(arguments, contract);
        if (asked instanceof final ListResourceMappingsCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return listed(((ListResourceMappingsCommand.Held) asked).command(), resolver, context);
    }

    private Answer listed(ListResourceMappingsCommand command, ResourceResolver resolver,
                          CallerContext context) {
        final Resource inventory = resolver.getResource(MAPPING_ROOT);
        if (inventory == null) {
            return new Failed(INVENTORY_FAILED, "the mapping inventory at " + MAPPING_ROOT
                    + " could not be read. That is refused rather than answered with an empty"
                    + " list, because a deployment with no mapping rules is an ordinary thing and"
                    + " a reader would have no reason to doubt the answer.");
        }
        final List<ListResourceMappingsResult.MappingEntry> entries = entriesUnder(inventory);
        if (entries.size() > context.discovery().limit()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this inventory holds more than the "
                    + context.discovery().limit() + " entries this caller may examine");
        }
        return new Produced(ListResourceMappingsResult.documentOf(
                pageOf(entries, command.window(), contract), ""));
    }

    /**
     * Every mapping entry under one inventory, in the order the platform applies them.
     *
     * <p>The platform applies them in the order the repository holds them, which is the order the
     * children come back in — so this preserves that order rather than imposing one. Sorting here
     * would be answering a different question that looks like this one.</p>
     *
     * @param inventory the mapping inventory
     * @return the entries, in application order, with no credential in any of them
     */
    public static List<ListResourceMappingsResult.MappingEntry> entriesUnder(Resource inventory) {
        final List<ListResourceMappingsResult.MappingEntry> entries = new ArrayList<>();
        gather(inventory, entries);
        return Collections.unmodifiableList(entries);
    }

    private static void gather(Resource under,
                               List<ListResourceMappingsResult.MappingEntry> into) {
        final java.util.Iterator<Resource> children = under.listChildren();
        while (children.hasNext()) {
            final Resource entry = children.next();
            described(entry).ifPresent(into::add);
            gather(entry, into);
        }
    }

    /**
     * What one node under the mapping table is, where it is a rule at all.
     *
     * <p>Which kind a rule is comes from the property that carries its replacement rather than from
     * the node's type: the platform keeps all four sorts in nodes of one type, and an operator
     * chasing an address that came out wrong is asking which sort they are looking at. A node
     * carrying none of the three is the hierarchy a rule sits in rather than a rule.</p>
     *
     * @param entry the node
     * @return the rule it is, or nothing where it is not one
     */
    private static java.util.Optional<ListResourceMappingsResult.MappingEntry> described(
            Resource entry) {
        for (final Map.Entry<String, MappingKind> carried : REPLACEMENT_PROPERTIES) {
            final List<String> replacements = valuesOf(entry, carried.getKey());
            if (!replacements.isEmpty()) {
                return java.util.Optional.of(new ListResourceMappingsResult.MappingEntry(
                        entry.getPath(), carried.getValue(), patternOf(entry), replacements,
                        entry.getValueMap().get(STATUS_PROPERTY,
                                ListResourceMappingsResult.NO_STATUS)));
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Which property carries a replacement, and what kind of rule that makes the entry.
     *
     * <p>Ordered, and the order decides: an entry carrying both an inward rewrite and an outward
     * redirect is reported as the rewrite, because that is what the platform applies first and
     * therefore what an operator is looking at when the address came out wrong.</p>
     */
    private static final List<Map.Entry<String, MappingKind>> REPLACEMENT_PROPERTIES = List.of(
            Map.entry(INTERNAL_REDIRECT_PROPERTY, MappingKind.INTERNAL_REDIRECT),
            Map.entry(REDIRECT_PROPERTY, MappingKind.REDIRECT),
            Map.entry(ALIAS_PROPERTY, MappingKind.ALIAS));

    /**
     * One property's values with every credential taken out, however many values it holds.
     *
     * <p>A mapping property holds one replacement or several, and the platform reads both spellings
     * the same way. Read as both here for the same reason: an operator whose rule lists three
     * alternatives needs to see three.</p>
     *
     * @param entry the node
     * @param property the property
     * @return its values, credential-free, and empty where it has none
     */
    private static List<String> valuesOf(Resource entry, String property) {
        final String[] several = entry.getValueMap().get(property, String[].class);
        final List<String> held = several == null
                ? List.of(entry.getValueMap().get(property, "")) : List.of(several);
        return held.stream()
                .filter(value -> !value.isEmpty())
                .map(ListResourceMappingsResult::withoutCredentials)
                .toList();
    }

    private static String patternOf(Resource entry) {
        final String declared = entry.getValueMap().get(MATCH_PROPERTY, "");
        return declared.isEmpty() ? entry.getName() : declared;
    }

    /**
     * The window's worth of entries.
     *
     * @param entries every entry, in application order
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the entries that page carries
     */
    public static List<ListResourceMappingsResult.MappingEntry> pageOf(
            List<ListResourceMappingsResult.MappingEntry> entries, ResultWindow window,
            AgentContract contract) {
        final long offset = window instanceof final ResultWindow.Initial initial
                ? initial.offset() : 0;
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit() : contract.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        return entries.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<String> categories() {
        return List.of(DISCOVERY_BUDGET_EXCEEDED, INVENTORY_FAILED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
