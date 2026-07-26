import { api } from "../../components/config/api";

export async function getSample() {
    try {
        const result = await api.get(`/wb/transmute/sample`);
        return result.data;
    } catch (error) {
        throw error;
    }
};

// Persist a BPMN process model. payload: { name, bpmnXml }
export async function saveModel(payload) {
    const result = await api.post(`/wb/transmute/model`, payload);
    return result.data;
}

// List saved process models.
export async function getModels() {
    const result = await api.get(`/wb/transmute/model`);
    return result.data;
}

// Load a single saved process model by id.
export async function getModel(id) {
    const result = await api.get(`/wb/transmute/model/${id}`);
    return result.data;
}

// Fetch a twin by id, including its full ordered eventLog (deploy → launch → connect →
// evolve attempts and why each was approved/blocked).
export async function getTwin(id) {
    const result = await api.get(`/wb/transmute/twin/${id}`);
    return result.data;
}

// Deploy a model and its twin onto the Spring Boot target platform.
// payload: { modelId }
export async function launchModel(payload) {
    const result = await api.post(`/wb/transmute/launch`, payload);
    return result.data;
}

// Link an activity in the original process to its counterpart in the twin, so the twin can
// act on behalf of that activity. Note: twinProcessId here is the TwinProcess id returned by
// launch (the twin handle), not the Camunda process-instance id.
// payload: { twinProcessId, originalActivityId, twinActivityId }
export async function connectActivity(payload) {
    const result = await api.post(`/wb/transmute/connect`, payload);
    return result.data;
}

// Ask the twin to evolve an activity by introducing an agent of the given type. The activity
// must be connected AND actually reached in the original process instance; governance and the
// node manager then decide whether the agent is approved. Returns an AgentDecision whose
// `approved`/`reason` fields carry the real outcome (the outer HTTP call succeeding does not).
// payload: { twinProcessId, activityId, agentType }
export async function evolveActivity(payload) {
    const result = await api.post(`/wb/transmute/evolve`, payload);
    return result.data;
}

// Automatic original-to-twin bridge: if the given activity has actually been reached in the
// original process instance, forwards it through the same evolve path evolveActivity uses.
// Safe to call more than once for the same activity -- idempotent on the twin. Returns the
// same AgentDecision shape as evolveActivity.
export async function bridgeActivity(twinProcessId, activityId) {
    const result = await api.post(`/wb/transmute/bridge/${twinProcessId}/${activityId}`);
    return result.data;
}