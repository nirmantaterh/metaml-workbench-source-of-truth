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

// backs "Edit Existing Project" - newest first, from the backend's own ordering
export async function listModels() {
    const result = await api.get(`/wb/transmute/model`);
    return result.data;
}

// payload: { modelId }. Preview only - nothing written to disk yet, see generateProject below
export async function generateDelegates(payload) {
    const result = await api.post(`/wb/transmute/generate`, payload);
    return result.data;
}

// payload: { modelId }. Assembles the real Spring Boot project on the server; doesn't launch it
export async function generateProject(payload) {
    const result = await api.post(`/wb/transmute/generate-project`, payload);
    return result.data;
}

// payload: { projectId }. Starts the generated project as its own app on an auto-assigned port
export async function launchProject(payload) {
    const result = await api.post(`/wb/transmute/launch-project`, payload);
    return result.data;
}

// payload: { projectId }
export async function stopProject(payload) {
    const result = await api.post(`/wb/transmute/stop-project`, payload);
    return result.data;
}

// what's currently launched, across every generated project - the Evolve step's own future
// "connect to an existing deployed application" reads from this same list
export async function listRunningProjects() {
    const result = await api.get(`/wb/transmute/running-projects`);
    return result.data;
}

// comes back with the twin's whole eventLog, in order
export async function getTwin(id) {
    const result = await api.get(`/wb/transmute/twin/${id}`);
    return result.data;
}

// payload: { modelId }
export async function launchModel(payload) {
    const result = await api.post(`/wb/transmute/launch`, payload);
    return result.data;
}

// twinProcessId is the id from launch, NOT either camunda process-instance id. lost an hour.
// payload: { twinProcessId, originalActivityId, twinActivityId }
export async function connectActivity(payload) {
    const result = await api.post(`/wb/transmute/connect`, payload);
    return result.data;
}

// returns an AgentDecision - check .approved, a 200 doesn't mean it went through
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

// every open task on the ORIGINAL instance, so the next activity becomes reachable
export async function completeCurrentTasks(twinProcessId) {
    const result = await api.post(`/wb/transmute/complete-task/${twinProcessId}`);
    return result.data;
}

// the policy is global server state, not per-twin
export async function getGovernancePolicy() {
    const result = await api.get(`/governance/policy`);
    return result.data;
}

// replaces the denylist, doesn't merge - load the current policy first or you'll wipe it
export async function updateGovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin) {
    const result = await api.post(`/governance/policy`, { deniedAgentTypes, maxEvolutionsPerTwin });
    return result.data;
}

// 404s if the twin was never launched
export async function getGovernanceUsage(twinProcessId) {
    const result = await api.get(`/governance/usage/${twinProcessId}`);
    return result.data;
}