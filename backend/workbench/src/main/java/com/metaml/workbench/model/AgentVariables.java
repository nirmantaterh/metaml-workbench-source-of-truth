package com.metaml.workbench.model;

import java.util.Collection;
import java.util.List;

// Shared naming contract: evolve writes evolvedAgent_* on the twin, AgentExecutionDelegate reads
// it on the original. Both ends must build the same name.
public final class AgentVariables {

    // predates the generic mechanism; bare name kept because Gateway_ChecksPassed in the citi model reads it
    public static final String RISK_FLAGGED_OUTPUT = "riskFlagged";

    private static final String OUTPUT_NAME_SEPARATOR = ",";

    private AgentVariables() {
    }

    public static String evolvedAgent(String twinActivityId, Object loopCounter) {
        return "evolvedAgent_" + perVisit(twinActivityId, loopCounter);
    }

    public static String evolvedAgentOutput(String outputName, String twinActivityId, Object loopCounter) {
        return "evolvedAgentOutput_" + outputName + "_" + perVisit(twinActivityId, loopCounter);
    }

    // tracks which outputs the last evolution wrote; re-evolution needs this to remove stale ones
    public static String evolvedAgentOutputIndex(String twinActivityId, Object loopCounter) {
        return "evolvedAgentOutputs_" + perVisit(twinActivityId, loopCounter);
    }

    public static String agentExecuted(String activityId, Object loopCounter) {
        return "agentExecuted_" + perVisit(activityId, loopCounter);
    }

    // written on the twin; the only signal that automation actually ran (vs. just having an agent assigned)
    public static String twinAutomation(String twinActivityId, Object loopCounter) {
        return "twinAutomation_" + perVisit(twinActivityId, loopCounter);
    }

    public static String twinAutomationOutput(String outputName, String twinActivityId, Object loopCounter) {
        return "twinAutomationOutput_" + outputName + "_" + perVisit(twinActivityId, loopCounter);
    }

    // not per-visit: gateway conditions read it without knowing which visit produced it; later visits may overwrite.
    public static String agentOutput(String activityId, String outputName) {
        return "agentOutput_" + activityId + "_" + outputName;
    }

    public static String outputIndexValue(Collection<String> outputNames) {
        return String.join(OUTPUT_NAME_SEPARATOR, outputNames);
    }

    public static List<String> outputNamesIn(Object indexValue) {
        if (!(indexValue instanceof String joined) || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(OUTPUT_NAME_SEPARATOR));
    }

    // multi-instance: same activity id per visit, so loop index is in the name to keep visits distinct
    private static String perVisit(String activityId, Object loopCounter) {
        return loopCounter == null ? activityId : activityId + "_" + loopCounter;
    }
}
