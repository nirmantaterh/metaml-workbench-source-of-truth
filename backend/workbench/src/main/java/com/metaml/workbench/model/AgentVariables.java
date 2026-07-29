package com.metaml.workbench.model;

// Evolve writes evolvedAgent_* on the twin instance and AgentExecutionDelegate reads it back on
// the original, so the two have to build the same name from opposite ends. Lives here rather
// than as a constant in either of them.
public final class AgentVariables {

    private AgentVariables() {
    }

    public static String evolvedAgent(String twinActivityId, Object loopCounter) {
        return "evolvedAgent_" + perVisit(twinActivityId, loopCounter);
    }

    public static String agentExecuted(String activityId, Object loopCounter) {
        return "agentExecuted_" + perVisit(activityId, loopCounter);
    }

    // A multi-instance activity is the same activity id several times over, each visit with its
    // own agent, so the loop index has to be part of the name or visit 3 overwrites 1 and 2 and
    // three evolutions end up looking like one. Plain activities have no loopCounter and keep
    // the short name they always had.
    private static String perVisit(String activityId, Object loopCounter) {
        return loopCounter == null ? activityId : activityId + "_" + loopCounter;
    }
}
