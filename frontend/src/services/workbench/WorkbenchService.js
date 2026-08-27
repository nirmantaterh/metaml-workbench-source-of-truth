import { api } from "../../components/config/api";

export async function getSample() {
    try {
        const result = await api.get(`/wb/transmute/sample`);
        return result.data;
    } catch (error) {
        throw error;
    }
};

export async function saveModel(payload) {
    const result = await api.post(`/wb/transmute/model`, payload);
    return result.data;
}

// payload: { id?, name, bpmnXml, twinBpmnXml, tenantId } - persists an independently authored second BPMN (e.g. Main + Twin) alongside the primary one; see ProcessModel.hasAuthoredTwin().
export async function saveModelWithAuthoredTwin(payload) {
    const result = await api.post(`/wb/transmute/model/authored-twin`, payload);
    return result.data;
}

export async function getModel(id) {
    const result = await api.get(`/wb/transmute/model/${id}`);
    return result.data;
}

// 409 when a generated app is still running; removes model+projects but preserves twins, Camunda state, and workflow history.
export async function deleteModel(id) {
    const result = await api.delete(`/wb/transmute/model/${id}`);
    return result.data;
}

export async function listModels() {
    const result = await api.get(`/wb/transmute/model`);
    return result.data;
}

// Every saved process across every project, each row carrying its own project id/display name - backs the Transmute > Generate / Launch pickers, which listModels() above can't (it has no notion of a project at all).
export async function listModelSummaries() {
    const result = await api.get(`/wb/transmute/model/summaries`);
    return result.data;
}

// preview only — nothing written to disk; see generateProject
export async function generateDelegates(payload) {
    const result = await api.post(`/wb/transmute/generate`, payload);
    return result.data;
}

// assembles the real Target Harness Platform (a Spring Boot app under the hood) on the server; doesn't launch it
export async function generateProject(payload) {
    const result = await api.post(`/wb/transmute/generate-project`, payload);
    return result.data;
}

export async function launchProject(payload) {
    const result = await api.post(`/wb/transmute/launch-project`, payload);
    return result.data;
}

export async function stopProject(payload) {
    const result = await api.post(`/wb/transmute/stop-project`, payload);
    return result.data;
}

// flat list across every generated project; source for "connect to existing" workflows
export async function listRunningProjects() {
    const result = await api.get(`/wb/transmute/running-projects`);
    return result.data;
}

// backend's authoritative pipeline state; never 404s (no history → all pending)
export async function getWorkflowState(modelId) {
    const result = await api.get(`/wb/transmute/model/${modelId}/workflow`);
    return result.data;
}

export async function getTwin(id) {
    const result = await api.get(`/wb/transmute/twin/${id}`);
    return result.data;
}

export async function launchModel(payload) {
    const result = await api.post(`/wb/transmute/launch`, payload);
    return result.data;
}

// twinProcessId is the id from launch, NOT either Camunda process-instance id.
export async function connectActivity(payload) {
    const result = await api.post(`/wb/transmute/connect`, payload);
    return result.data;
}

// returns AgentDecision; a 200 doesn't mean approved — check .approved
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

// tenant-scoped policy lifecycle — every call takes ids returned by earlier calls, none guessable

export async function listTenants() {
    const result = await api.get(`/governance/tenants`);
    return result.data;
}

export async function createTenant(payload) {
    const result = await api.post(`/governance/tenants`, payload);
    return result.data;
}

export async function listTenantPolicies(tenantId) {
    const result = await api.get(`/governance/tenants/${tenantId}/policies`);
    return result.data;
}

export async function createTenantPolicy(tenantId, payload) {
    const result = await api.post(`/governance/tenants/${tenantId}/policies`, payload);
    return result.data;
}

// tenantId is a query param here (reads); mutations take it in the body.
export async function listPolicyVersions(policyId, tenantId) {
    const result = await api.get(`/governance/policies/${policyId}/policy-versions`, { params: { tenantId } });
    return result.data;
}

// always starts empty; rules are added separately
export async function createDraftVersion(policyId, payload) {
    const result = await api.post(`/governance/policies/${policyId}/policy-versions`, payload);
    return result.data;
}

// 409s if the version isn't DRAFT
export async function addPolicyRule(versionId, payload) {
    const result = await api.post(`/governance/policy-versions/${versionId}/rules`, payload);
    return result.data;
}

// retires the previously ACTIVE version server-side; re-fetch to see its status change
export async function activatePolicyVersion(versionId, payload) {
    const result = await api.post(`/governance/policy-versions/${versionId}/activate`, payload);
    return result.data;
}

// always evaluates the ACTIVE version, never a DRAFT.
export async function evaluatePolicy(payload) {
    const result = await api.post(`/governance/evaluate`, payload);
    return result.data;
}

// approval endpoints — /transmute not /governance because these resolve a paused twin evolution

// PENDING and resolved alike, unordered — caller sorts if it cares
export async function listApprovals(tenantId) {
    const result = await api.get(`/wb/transmute/evolve/approvals`, { params: { tenantId } });
    return result.data;
}

// returns AgentDecision (not Approval); re-fetch listApprovals for the real status.
export async function approveEvolution(approvalId, payload) {
    const result = await api.post(`/wb/transmute/evolve/approvals/${approvalId}/approve`, payload);
    return result.data;
}

// same AgentDecision-not-Approval caveat as approveEvolution
export async function rejectApproval(approvalId, payload) {
    const result = await api.post(`/wb/transmute/evolve/approvals/${approvalId}/reject`, payload);
    return result.data;
}