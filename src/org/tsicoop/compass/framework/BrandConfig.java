package org.tsicoop.compass.framework;

import java.util.Collections;
import java.util.List;

/**
 * Central access point for the partner/reseller brand name (BRAND_NAME env var).
 *
 * The default brand "TSI Compass" is exactly 11 characters. The 11-character cap
 * on BRAND_NAME is a deliberate drop-in-replacement constraint: it guarantees a
 * partner's brand fits every layout (sidebar widths, title bars, report footers)
 * that was designed around the default string, with zero redesign risk. A brand
 * that exceeds the limit fails the application at startup, mirroring how
 * JWT_SECRET / DB_ENCRYPTION_KEY are validated (see JWTUtil, DbEncryption).
 */
public class BrandConfig {

    public static final String ENV_VAR = "BRAND_NAME";
    public static final String DEFAULT_BRAND = "TSI Compass";
    public static final int MAX_LENGTH = DEFAULT_BRAND.length();

    /** Brand-token variants found across web/ — currently just the full name. */
    private static final List<String> BRAND_TOKENS = Collections.singletonList(DEFAULT_BRAND);

    private static final String brand;

    static {
        String configured = System.getenv(ENV_VAR);
        if (configured == null || configured.trim().isEmpty()) {
            brand = DEFAULT_BRAND;
        } else {
            String trimmed = configured.trim();
            if (trimmed.length() > MAX_LENGTH) {
                throw new IllegalStateException(
                        ENV_VAR + " must be at most " + MAX_LENGTH + " characters " +
                        "(got " + trimmed.length() + ": '" + trimmed + "'). " +
                        "The limit matches the length of the default brand '" + DEFAULT_BRAND +
                        "' so that partner brands are guaranteed to fit existing layouts.");
            }
            brand = trimmed;
        }
    }

    private BrandConfig() {}

    /** The active brand name: the configured partner brand, or the default. */
    public static String name() {
        return brand;
    }

    /** True only when a non-default brand is active — lets callers skip rewriting work in the common case. */
    public static boolean isCustomized() {
        return !DEFAULT_BRAND.equals(brand);
    }

    /** Brand-token variants to replace, longest match first. Each maps to {@link #name()}. */
    public static List<String> tokens() {
        return BRAND_TOKENS;
    }

    /**
     * Up to two initials for the sidebar logo badge (e.g. "TSI Compass" -> "TC",
     * a single-word brand "Acme" -> "A"). Derived from the first letter of each
     * whitespace-separated word, since partner brands aren't guaranteed to be
     * two words like the default.
     */
    public static String initials() {
        String[] words = brand.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.length() > 0 ? sb.toString() : DEFAULT_BRAND.substring(0, 2);
    }
}
