package com.metaml.workbench.model;

import java.util.Collection;
import java.util.List;

// Evolve writes evolvedAgent_* on the twin instance and AgentExecutionDelegate reads it back on
// the original, so the two have to build the same name from opposite ends. Lives here rather
// than as a constant in either of them.
public final class AgentVariables {

    // the output name that predates the generic mechanism. It still gets copied onto the original
    // under its old bare name as well, because Gateway_ChecksPassed in the citi model asks for
    // that name and nothing else.
    public static final String RISK_FLAGGED_OUTPUT = "riskFlagged";

    private static final String OUTPUT_NAME_SEPARATOR = ",";

    private AgentVariables() {
    }

    public static String evolvedAgent(String twinActivityId, Object loopCounter) {
        return "evolvedAgent_" + perVisit(twinActivityId, loopCounter);
    }

    // one variable per named output the agent reported, so a catalog entry can report several
    // things without any of them needing a field of its own anywhere
    public static String evolvedAgentOutput(String outputName, String twinActivityId, Object loopCounter) {
        return "evolvedAgentOutput_" + outputName + "_" + perVisit(twinActivityId, loopCounter);
    }

    // which of the above the last evolution of this visit wrote. Without it a re-evolution can
    // only add outputs, never take away one the previous agent left behind.
    public static String evolvedAgentOutputIndex(String twinActivityId, Object loopCounter) {
        return "evolvedAgentOutputs_" + perVisit(twinActivityId, loopCounter);
    }

    public static String agentExecuted(String activityId, Object loopCounter) {
        return "agentExecuted_" + perVisit(activityId, loopCounter);
    }

    // Deliberately not per-visit like the names above: gateway conditions downstream in the model
    // read this by hand and have no idea which visit of the activity produced it, same as the
    // agentFlaggedRisk flag it generalises. Nothing reconciles this one the way the twin side
    // does, so a multi-instance activity whose later visits report fewer outputs than an earlier
    // one leaves the earlier value sitting here, attributed to whichever visit last touched it.
    // Neither example model does this today; worth an index here too if one ever does.
    public static String agentOutput(String activityId, String outputName) {
        return "agentOutput_" + activityId + "_" + outputName;
    }

    // the index variable is just the output names joined up, and both ends have to agree on that
    // the same way they agree on the names, so the two halves of it live together
    public static String outputIndexValue(Collection<String> outputNames) {
        return String.join(OUTPUT_NAME_SEPARATOR, outputNames);
    }

    public static List<String> outputNamesIn(Object indexValue) {
        if (!(indexValue instanceof String joined) || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(OUTPUT_NAME_SEPARATOR));
    }

    // A multi-instance activity is the same activity id several times over, each visit with its
    // own agent, so the loop index has to be part of the name or visit 3 overwrites 1 and 2 and
    // three evolutions end up looking like one. Plain activities have no loopCounter and keep
    // the short name they always had.
    private static String perVisit(String activityId, Object loopCounter) {
        return loopCounter == null ? activityId : activityId + "_" + loopCounter;
    }
}
