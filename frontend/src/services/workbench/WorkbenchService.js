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

// Authoring/catalog delete: removes the model, its .bpmn and its generated projects. Twins,
// Camunda runtime state, approvals and workflow history are all kept by design. 409 when one of
// the model's generated apps is still running - stop it first. The id can never be reused.
export async function deleteModel(id) {
    const result = await api.delete(`/wb/transmute/model/${id}`);
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

// Single source of truth for the Model -> Generate -> Launch breadcrumb - the backend's real
// record of what's happened to this model's pipeline, not something the frontend infers from its
// own local state. Never 404s - a model with no history yet just reads back as everything pending.
export async function getWorkflowState(modelId) {
    const result = await api.get(`/wb/transmute/model/${modelId}/workflow`);
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

// --- Phase 6C: tenant-scoped policy lifecycle (Phase 1/2's own API, nothing new on the
// backend - see the Phase 6B audit). Every call below either creates a Tenant/Policy id or
// takes one already returned by an earlier call; none of it is guessable.

export async function listTenants() {
    const result = await api.get(`/governance/tenants`);
    return result.data;
}

// payload: { name }
export async function createTenant(payload) {
    const result = await api.post(`/governance/tenants`, payload);
    return result.data;
}

export async function listTenantPolicies(tenantId) {
    const result = await api.get(`/governance/tenants/${tenantId}/policies`);
    return result.data;
}

// payload: { name }
export async function createTenantPolicy(tenantId, payload) {
    const result = await api.post(`/governance/tenants/${tenantId}/policies`, payload);
    return result.data;
}

// tenantId is a query param here, matching the backend's own read-path convention (see
// TenantPolicyController) - only mutations take it in the body
export async function listPolicyVersions(policyId, tenantId) {
    const result = await api.get(`/governance/policies/${policyId}/policy-versions`, { params: { tenantId } });
    return result.data;
}

// payload: { tenantId }. Always starts empty - see Phase 6B, that's the whole editing model
export async function createDraftVersion(policyId, payload) {
    const result = await api.post(`/governance/policies/${policyId}/policy-versions`, payload);
    return result.data;
}

// payload: { tenantId, field, operator, value, effect }. 409s if the version isn't DRAFT
export async function addPolicyRule(versionId, payload) {
    const result = await api.post(`/governance/policy-versions/${versionId}/rules`, payload);
    return result.data;
}

// payload: { tenantId }. Retires whatever was ACTIVE before it, backend-side - nothing for the
// frontend to do about the old version except re-fetch and see its status change
export async function activatePolicyVersion(versionId, payload) {
    const result = await api.post(`/governance/policy-versions/${versionId}/activate`, payload);
    return result.data;
}

// Phase 6D: PolicyDecisionEngine.evaluate() - already existed (Phase 2), never had a frontend
// caller until now. Always evaluates whatever is ACTIVE for the tenant, never a DRAFT - there
// is no way to ask it to evaluate a specific version, on purpose (see PolicyDecisionEngine).
// payload: { tenantId, action, attributes }
export async function evaluatePolicy(payload) {
    const result = await api.post(`/governance/evaluate`, payload);
    return result.data;
}

// Phase 4's approval endpoints (already existed, no frontend caller until now) - a plain
// /transmute path like connect/evolve/bridge above, not under /governance, since these resolve
// a specific twin's paused evolution rather than editing policy state.

// PENDING and resolved alike, unordered - the caller sorts if it cares (it does; see the page)
export async function listApprovals(tenantId) {
    const result = await api.get(`/wb/transmute/evolve/approvals`, { params: { tenantId } });
    return result.data;
}

// payload: { tenantId }. Returns an AgentDecision, not the updated Approval - approveEvolution
// resumes and runs the original operation synchronously, so by the time this resolves the
// approval is already COMPLETED or FAILED, not sitting at APPROVED. Re-fetch listApprovals for
// the real status rather than reading it off this response.
export async function approveEvolution(approvalId, payload) {
    const result = await api.post(`/wb/transmute/evolve/approvals/${approvalId}/approve`, payload);
    return result.data;
}

// payload: { tenantId }. Same AgentDecision-not-Approval caveat as approveEvolution above.
export async function rejectApproval(approvalId, payload) {
    const result = await api.post(`/wb/transmute/evolve/approvals/${approvalId}/reject`, payload);
    return result.data;
}