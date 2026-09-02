// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.command.platform.ConfigurationCatalogues;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The four commands about the platform's own configuration, two of which a deployment may refuse.
 *
 * <p>The control gate is asked before the argument is even read on the two that write. That order
 * is deliberate: a caller on an environment that does not keep configuration changes should be told
 * that, not told their assignments were malformed. The refusal they need is about where they are
 * running, and telling them about a typo first sends them to fix the wrong thing.</p>
 *
 * <p>The two that read pass through no gate at all. An environment whose configuration is immutable
 * still answers questions about it, and on such an environment that is most of what an operator
 * wants — they cannot change it, so knowing exactly what it says is the whole job.</p>
 */
public final class ConfigurationHandler implements CommandHandler {

    /** Which of the four this handler answers. */
    public enum Kind {
        /** Lists configurations by identifier prefix. */
        SEARCH,
        /** Reads one configuration's properties. */
        INSPECTION,
        /** Changes one. */
        UPDATE,
        /** Removes one. */
        REMOVAL
    }

    private final AgentContract contract;
    private final Kind kind;
    private final ConfigurationCatalogues catalogues;
    private final PlatformControl control;

    /**
     * Holds one handler for one of the four.
     *
     * @param contract the authenticated contract
     * @param kind which of the four commands this handler answers
     * @param catalogues where one run gets its own view of the configurations
     * @param control what this deployment permits, asked before either change proceeds
     */
    public ConfigurationHandler(AgentContract contract, Kind kind,
                                ConfigurationCatalogues catalogues, PlatformControl control) {
        this.contract = contract;
        this.kind = kind;
        this.catalogues = catalogues;
        this.control = control;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case SEARCH -> searched(arguments, context);
            case INSPECTION -> inspected(arguments);
            case UPDATE -> permitted(() -> changed(arguments));
            case REMOVAL -> permitted(() -> removed(arguments));
        };
    }

    /** What one guarded command does once the deployment has permitted it. */
    @FunctionalInterface
    private interface Guarded {

        /**
         * Runs it.
         *
         * @return the answer
         */
        Answer run();
    }

    private Answer permitted(Guarded guarded) {
        final PlatformControl.Verdict verdict =
                control.permits(ControlCapability.CONFIGURATION_CHANGE);
        return verdict instanceof final PlatformControl.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : guarded.run();
    }

    private Answer searched(DocumentValue.Mapping arguments, CallerContext context) {
        final FindConfigurationsCommand.Outcome asked =
                FindConfigurationsCommand.of(arguments, contract);
        if (asked instanceof final FindConfigurationsCommand.Refused refused) {
            return new Failed(ConfigurationHandlers.LOOKUP_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final FindConfigurationsCommand command =
                ((FindConfigurationsCommand.Held) asked).command();
        final ConfigurationCatalogue.Outcome found =
                catalogues.open().find(command.prefix(), context.discovery().limit());
        if (found instanceof final ConfigurationCatalogue.Failed failed) {
            return new Failed(failed.category(), failed.detail());
        }
        final List<ConfigurationCatalogue.Entry> entries =
                ((ConfigurationCatalogue.Listed) found).entries();
        return entries.size() > context.discovery().limit()
                ? new Failed(ConfigurationHandlers.DISCOVERY_BUDGET_EXCEEDED, entries.size()
                        + " configurations is more than the " + context.discovery().limit()
                        + " this caller may examine")
                : new Produced(FindConfigurationsResult.documentOf(
                        pageOf(entries, command.window()),
                        FindConfigurationsResult.NO_MORE_PAGES));
    }

    /**
     * The window's worth of entries.
     *
     * @param entries every entry the platform holds, in its own order
     * @param window which page is wanted
     * @return the entries that page carries
     */
    public static List<ConfigurationCatalogue.Entry> pageOf(
            List<ConfigurationCatalogue.Entry> entries, ResultWindow window) {
        if (!(window instanceof final ResultWindow.Initial initial)) {
            return entries;
        }
        return entries.stream().skip(initial.offset()).limit(initial.limit()).toList();
    }

    private Answer inspected(DocumentValue.Mapping arguments) {
        final ConfigurationIdentifierCommand.Outcome asked =
                ConfigurationIdentifierCommand.of(arguments, contract);
        if (asked instanceof final ConfigurationIdentifierCommand.Refused refused) {
            return new Failed(ConfigurationHandlers.LOOKUP_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final String identifier =
                ((ConfigurationIdentifierCommand.Held) asked).command().persistentIdentifier();
        final ConfigurationCatalogue.Outcome read = catalogues.open().inspect(identifier);
        return read instanceof final ConfigurationCatalogue.Failed failed
                ? new Failed(failed.category(), failed.detail())
                : new Produced(InspectConfigurationResult.documentOf(identifier,
                        (ConfigurationCatalogue.Inspected) read));
    }

    private Answer changed(DocumentValue.Mapping arguments) {
        final UpdateConfigurationCommand.Outcome asked =
                UpdateConfigurationCommand.of(arguments, contract);
        if (asked instanceof final UpdateConfigurationCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        final UpdateConfigurationCommand command =
                ((UpdateConfigurationCommand.Held) asked).command();
        final ConfigurationCatalogue.Outcome written = catalogues.open().apply(
                command.persistentIdentifier(), command.assignments(),
                command.removedPropertyKeys());
        return written instanceof final ConfigurationCatalogue.Failed failed
                ? new Failed(failed.category(), failed.detail())
                : new Produced(UpdateConfigurationResult.documentOf(
                        command.persistentIdentifier(),
                        ((ConfigurationCatalogue.Changed) written).changedPropertyKeyCount()));
    }

    /**
     * Which declared category one change refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(UpdateConfigurationCommand.Refusal refusal) {
        return switch (refusal) {
            case VALUE_REJECTED -> ConfigurationHandlers.VALUE_UNSUPPORTED;
            // Too many is malformed rather than a budget: the client's own schema bounds how many
            // assignments one document carries, so a document past it is outside the contract
            // rather than inside it and too large. The change row declares no budget category at
            // all, and answering one it does not declare is how a caller gets a failure their own
            // half has never heard of.
            case TOO_MANY, ASSIGNMENTS_REJECTED, SET_AND_REMOVED, REMOVALS_REJECTED ->
                    ConfigurationHandlers.VALUE_MALFORMED;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, IDENTIFIER_REJECTED ->
                    ConfigurationHandlers.LOOKUP_FAILED;
        };
    }

    private Answer removed(DocumentValue.Mapping arguments) {
        final ConfigurationIdentifierCommand.Outcome asked =
                ConfigurationIdentifierCommand.of(arguments, contract);
        if (asked instanceof final ConfigurationIdentifierCommand.Refused refused) {
            return new Failed(ConfigurationHandlers.LOOKUP_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final String identifier =
                ((ConfigurationIdentifierCommand.Held) asked).command().persistentIdentifier();
        final ConfigurationCatalogue.Outcome gone = catalogues.open().erase(identifier);
        return gone instanceof final ConfigurationCatalogue.Failed failed
                ? new Failed(failed.category(), failed.detail())
                : new Produced(DeleteConfigurationResult.documentOf(identifier,
                        ((ConfigurationCatalogue.Changed) gone).origin()));
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case SEARCH -> ConfigurationHandlers.searchCategories();
            case INSPECTION -> ConfigurationHandlers.inspectionCategories();
            case UPDATE -> ConfigurationHandlers.updateCategories();
            case REMOVAL -> ConfigurationHandlers.removalCategories();
        };
    }
}
