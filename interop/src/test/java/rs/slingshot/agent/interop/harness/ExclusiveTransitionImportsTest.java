// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Probe imports remain resolvable when a product begins importing a supplemental interface. */
final class ExclusiveTransitionImportsTest {

    private static final String SUPPLEMENTAL = "org.osgi.framework.wiring,javax.jcr.version,"
            + "javax.jcr.lock,javax.jcr.security,javax.jcr.retention,org.xml.sax";

    @Test
    void allMissingInterfacesRemainAvailableToTheProbe() {
        assertEquals(SUPPLEMENTAL + ",javax.jcr;version=\"[2.0,3)\"",
                ExclusiveTransitionRuntime.importsFor("javax.jcr;version=\"[2.0,3)\""));
    }

    @Test
    void theProductLockImportRetainsItsRangeWithoutADuplicate() {
        final String product = "javax.jcr;version=\"[2.0,3)\",javax.jcr.lock;version=\"[2.0,3)\"";
        assertEquals("org.osgi.framework.wiring,javax.jcr.version,javax.jcr.security,"
                        + "javax.jcr.retention,org.xml.sax," + product,
                ExclusiveTransitionRuntime.importsFor(product));
    }

    @Test
    void existingSupplementalInterfacesRetainAllProductClauses() {
        final String product = "org.osgi.framework.wiring;version=\"[1.2,2)\","
                + "javax.jcr.version;version=\"[2.0,3)\",javax.jcr.lock;version=\"[2.0,3)\","
                + "javax.jcr.security;version=\"[2.0,3)\",javax.jcr.retention;version=\"[2.0,3)\","
                + "org.xml.sax;version=\"[2.0,3)\"";
        assertEquals(product, ExclusiveTransitionRuntime.importsFor(product));
    }

    @Test
    void aSimilarPackageNameDoesNotSatisfyTheRequiredInterface() {
        final String product = "synthetic.javax.jcr.lock;version=\"[2.0,3)\"";
        assertEquals(SUPPLEMENTAL + "," + product, ExclusiveTransitionRuntime.importsFor(product));
    }
}
