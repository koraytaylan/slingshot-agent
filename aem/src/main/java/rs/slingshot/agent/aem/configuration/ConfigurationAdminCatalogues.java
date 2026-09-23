// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.configuration;

import java.util.function.Supplier;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.MetaTypeService;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.command.platform.ConfigurationCatalogues;

/**
 * Where each configuration command gets its view of the platform's configuration admin.
 *
 * <p>Every run is given a view of its own, though the view holds nothing between calls; what is
 * held here is only how to open one, including how to reach the framework's bundles, which is
 * where the meta type service finds the descriptions that decide what may be reported.</p>
 */
@Component(service = ConfigurationCatalogues.class)
public final class ConfigurationAdminCatalogues implements ConfigurationCatalogues {

    /** Opens one view onto the configuration admin. */
    private final Supplier<ConfigurationCatalogue> opening;

    /**
     * Holds the view the platform's configuration admin and meta type service answer.
     *
     * @param context this bundle's own context, through which the framework's bundles are read
     * @param admin the configuration admin
     * @param metatypes the meta type service
     */
    @Activate
    public ConfigurationAdminCatalogues(BundleContext context,
                                        @Reference ConfigurationAdmin admin,
                                        @Reference MetaTypeService metatypes) {
        this.opening = () -> new DefaultConfigurationCatalogue(() -> bundlesOf(context), admin,
                metatypes);
    }

    @Override
    public ConfigurationCatalogue open() {
        return opening.get();
    }

    private static Bundle[] bundlesOf(BundleContext context) {
        return context.getBundles();
    }
}
