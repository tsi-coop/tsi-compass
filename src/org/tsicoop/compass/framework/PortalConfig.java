package org.tsicoop.compass.framework;

/**
 * Exposure switch for the vendor portal. Off unless TSI_VENDOR_PORTAL_ENABLED=true, so a default
 * deployment has no vendor-facing surface: VENDOR logins are refused, /vendorportal/* returns 404
 * and the portal API answers 403. Read on each call so it follows the environment of the process.
 */
public final class PortalConfig {
    private PortalConfig() {}

    public static boolean vendorPortalEnabled() {
        return "true".equalsIgnoreCase(System.getenv("TSI_VENDOR_PORTAL_ENABLED"));
    }
}
