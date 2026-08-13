package com.metaml.workbench.model;

// Centralizes business-key prefixes; drift across callers silently breaks all auto-bridging.
public final class BusinessKeys {

    private static final String ORIGINAL_PREFIX = "original-";
    private static final String TWIN_PREFIX = "twin-";

    private BusinessKeys() {
    }

    public static String originalKey(String twinId) {
        return ORIGINAL_PREFIX + twinId;
    }

    public static String twinKey(String twinId) {
        return TWIN_PREFIX + twinId;
    }

    public static boolean isOriginalKey(String businessKey) {
        return businessKey != null && businessKey.startsWith(ORIGINAL_PREFIX);
    }

    public static boolean isTwinKey(String businessKey) {
        return businessKey != null && businessKey.startsWith(TWIN_PREFIX);
    }

    // caller must have checked isOriginalKey first; no bounds guard
    public static String twinIdFromOriginalKey(String businessKey) {
        return businessKey.substring(ORIGINAL_PREFIX.length());
    }

    public static String twinIdFromTwinKey(String businessKey) {
        return businessKey.substring(TWIN_PREFIX.length());
    }
}
