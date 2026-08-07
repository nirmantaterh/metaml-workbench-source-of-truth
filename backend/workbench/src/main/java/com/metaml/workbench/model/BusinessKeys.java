package com.metaml.workbench.model;

// Phase 9/10 red team finding: the "original-"/"twin-" business-key convention that ties an
// Original process instance back to its Twin (and back) used to be redeclared as an independent
// literal/constant in four separate places (WorkbenchServiceImpl, AutoBridgeTrigger,
// AgentExecutionDelegate, TwinAutomationDelegate), with nothing cross-referencing them - each class
// simply "happened to agree". A drift in any one of the four would have no failure mode beyond
// businessKey.startsWith(prefix) silently returning false everywhere it's checked, quietly
// disabling all auto-bridging with no error, the same pattern AgentVariables already solved for the
// evolvedAgent_*/twinAutomation_* naming convention. One shared source of truth for both prefixes now.
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

    // caller must already have checked isOriginalKey - same contract the four inline
    // substring(prefix.length()) call sites this replaces always had
    public static String twinIdFromOriginalKey(String businessKey) {
        return businessKey.substring(ORIGINAL_PREFIX.length());
    }

    public static String twinIdFromTwinKey(String businessKey) {
        return businessKey.substring(TWIN_PREFIX.length());
    }
}
