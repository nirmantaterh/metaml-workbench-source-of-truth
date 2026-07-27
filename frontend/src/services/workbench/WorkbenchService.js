import { api } from "../../components/config/api";

export async function getSample() {
    try {
        const result = await api.get(`/wb/transmute/sample`);
        return result.data;
    } catch (error) {
        throw error;
    }
};

// payload: { name, bpmnXml }
export async function saveModel(payload) {
    const result = await api.post(`/wb/transmute/model`, payload);
    return result.data;
}

export async function getModel(id) {
    const result = await api.get(`/wb/transmute/model/${id}`);
    return result.data;
}

// includes the twin's full ordered eventLog
export async function getTwin(id) {
    const result = await api.get(`/wb/transmute/twin/${id}`);
    return result.data;
}

// payload: { modelId }
export async function launchModel(payload) {
    const result = await api.post(`/wb/transmute/launch`, payload);
    return result.data;
}

// twinProcessId is the TwinProcess id from launch, NOT the Camunda process-instance id
// payload: { twinProcessId, originalActivityId, twinActivityId }
export async function connectActivity(payload) {
    const result = await api.post(`/wb/transmute/connect`, payload);
    return result.data;
}

// Returns an AgentDecision - check .approved, a 200 here doesn't mean it went through.
// payload: { twinProcessId, activityId, agentType }
export async function evolveActivity(payload) {
    const result = await api.post(`/wb/transmute/evolve`, payload);
    return result.data;
}

// idempotent, same AgentDecision shape as evolveActivity
export async function bridgeActivity(twinProcessId, activityId) {
    const result = await api.post(`/wb/transmute/bridge/${twinProcessId}/${activityId}`);
    return result.data;
}

// Completes every open task on the ORIGINAL instance so the next activity becomes reachable.
// All of them, not a chosen one - a parallel gateway leaves several open at once.
export async function completeCurrentTasks(twinProcessId) {
    const result = await api.post(`/wb/transmute/complete-task/${twinProcessId}`);
    return result.data;
}

// the policy is global server state, not per-twin
export async function getGovernancePolicy() {
    const result = await api.get(`/governance/policy`);
    return result.data;
}

// replaces the denylist rather than merging, so load the current policy first
export async function updateGovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin) {
    const result = await api.post(`/governance/policy`, { deniedAgentTypes, maxEvolutionsPerTwin });
    return result.data;
}

// 404s if the twin was never launched
export async function getGovernanceUsage(twinProcessId) {
    const result = await api.get(`/governance/usage/${twinProcessId}`);
    return result.data;
}