import React, { useCallback, useEffect, useState } from "react";
import { Container, Table, Button, Badge, Form, Row, Col } from "react-bootstrap";

import {
    listTenants,
    createTenant,
    listTenantPolicies,
    createTenantPolicy,
    listPolicyVersions,
    createDraftVersion,
    addPolicyRule,
    activatePolicyVersion,
    evaluatePolicy,
} from "../../services/workbench/WorkbenchService";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

// Refetches after mutations because policy state changes only through this page.
const EFFECTS = ["ALLOW", "DENY", "REQUIRE_APPROVAL"];
const OPERATORS = ["==", "!=", ">", ">=", "<", "<="];

const STATUS_VARIANT = { ACTIVE: "success", DRAFT: "secondary", RETIRED: "dark" };

const GovernancePoliciesPage = () => {
    const [tenants, setTenants] = useState([]);
    const [tenantId, setTenantId] = useState("");
    const [newTenantName, setNewTenantName] = useState("");

    const [policies, setPolicies] = useState([]);
    const [loadingPolicies, setLoadingPolicies] = useState(false);
    const [policiesError, setPoliciesError] = useState(null);
    const [newPolicyName, setNewPolicyName] = useState("");

    // undefined = not yet expanded; null/[] = loaded
    const [versionsByPolicyId, setVersionsByPolicyId] = useState({});
    const [ruleForms, setRuleForms] = useState({});
    const [evalForms, setEvalForms] = useState({});
    const [evalResults, setEvalResults] = useState({});
    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState(null);

    const errorText = (err) => err.response?.data?.message || err.message;

    const refreshTenants = useCallback(async () => {
        try {
            const res = await listTenants();
            setTenants(res.data || res || []);
        } catch (err) {
            setStatus({ type: "err", text: "Loading tenants failed: " + errorText(err) });
        }
    }, []);

    useEffect(() => {
        refreshTenants();
    }, [refreshTenants]);

    const handleCreateTenant = async () => {
        const name = newTenantName.trim();
        if (!name) return;
        setBusy(true);
        try {
            const res = await createTenant({ name });
            const tenant = res.data || res;
            await refreshTenants();
            setTenantId(tenant.id);
            setNewTenantName("");
            setStatus({ type: "ok", text: `Created tenant "${tenant.name}".` });
        } catch (err) {
            setStatus({ type: "err", text: "Create tenant failed: " + errorText(err) });
        } finally {
            setBusy(false);
        }
    };

    const refreshPolicies = useCallback(async (forTenantId) => {
        if (!forTenantId) {
            setPolicies([]);
            return;
        }
        setLoadingPolicies(true);
        try {
            const res = await listTenantPolicies(forTenantId);
            setPolicies(res.data || res || []);
            setPoliciesError(null);
        } catch (err) {
            setPolicies([]);
            setPoliciesError(errorText(err));
        } finally {
            setLoadingPolicies(false);
        }
    }, []);

    useEffect(() => {
        setVersionsByPolicyId({});
        refreshPolicies(tenantId);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantId]);

    const handleCreatePolicy = async () => {
        const name = newPolicyName.trim();
        if (!tenantId || !name) return;
        setBusy(true);
        try {
            await createTenantPolicy(tenantId, { name });
            setNewPolicyName("");
            await refreshPolicies(tenantId);
            setStatus({ type: "ok", text: `Created policy "${name}".` });
        } catch (err) {
            setStatus({ type: "err", text: "Create policy failed: " + errorText(err) });
        } finally {
            setBusy(false);
        }
    };

    const refreshVersions = async (policyId) => {
        try {
            const res = await listPolicyVersions(policyId, tenantId);
            const versions = (res.data || res || []).slice().sort((a, b) => a.versionNumber - b.versionNumber);
            setVersionsByPolicyId((prev) => ({ ...prev, [policyId]: versions }));
        } catch (err) {
            setStatus({ type: "err", text: "Loading versions failed: " + errorText(err) });
        }
    };

    const toggleExpand = async (policyId) => {
        if (versionsByPolicyId[policyId] !== undefined) {
            setVersionsByPolicyId((prev) => ({ ...prev, [policyId]: undefined }));
            return;
        }
        await refreshVersions(policyId);
    };

    const handleCreateDraft = async (policyId) => {
        setBusy(true);
        try {
            const res = await createDraftVersion(policyId, { tenantId });
            const version = res.data || res;
            await refreshVersions(policyId);
            setStatus({ type: "ok", text: `Created draft v${version.versionNumber}. It does not affect evaluation until activated.` });
        } catch (err) {
            setStatus({ type: "err", text: "Create draft failed: " + errorText(err) });
        } finally {
            setBusy(false);
        }
    };

    const ruleForm = (versionId) => ruleForms[versionId] || { field: "", operator: ">", value: "", effect: "REQUIRE_APPROVAL" };
    const setRuleForm = (versionId, patch) =>
        setRuleForms((prev) => ({ ...prev, [versionId]: { ...ruleForm(versionId), ...patch } }));

    const handleAddRule = async (policyId, versionId) => {
        const form = ruleForm(versionId);
        if (!form.field.trim()) {
            setStatus({ type: "err", text: "Enter a field name before adding the rule." });
            return;
        }
        setBusy(true);
        try {
            await addPolicyRule(versionId, {
                tenantId,
                field: form.field.trim(),
                operator: form.operator,
                value: form.value,
                effect: form.effect,
            });
            setRuleForms((prev) => ({ ...prev, [versionId]: undefined }));
            await refreshVersions(policyId);
            setStatus({ type: "ok", text: "Rule added to the draft." });
        } catch (err) {
            setStatus({ type: "err", text: "Add rule failed: " + errorText(err) });
        } finally {
            setBusy(false);
        }
    };

    const handleActivate = async (policyId, versionId, versionNumber) => {
        setBusy(true);
        try {
            await activatePolicyVersion(versionId, { tenantId });
            await refreshVersions(policyId);
            setStatus({ type: "ok", text: `Activated v${versionNumber}. Policy evaluation now uses this version.` });
        } catch (err) {
            setStatus({ type: "err", text: "Activate failed: " + errorText(err) });
        } finally {
            setBusy(false);
        }
    };

    // always evaluates the ACTIVE version — no parameter to target a draft, by design
    const evalForm = (policyId) => evalForms[policyId] || { action: "", field: "", value: "" };
    const setEvalForm = (policyId, patch) =>
        setEvalForms((prev) => ({ ...prev, [policyId]: { ...evalForm(policyId), ...patch } }));

    const handleEvaluate = async (policyId) => {
        const form = evalForm(policyId);
        setBusy(true);
        try {
            const res = await evaluatePolicy({
                tenantId,
                action: form.action.trim(),
                attributes: form.field.trim() ? { [form.field.trim()]: form.value } : {},
            });
            setEvalResults((prev) => ({ ...prev, [policyId]: res.data || res }));
        } catch (err) {
            setEvalResults((prev) => ({ ...prev, [policyId]: { error: errorText(err) } }));
        } finally {
            setBusy(false);
        }
    };

    const statusClass = status?.type === "err" ? "text-danger" : "text-success";

    return (
        <Container className="pt-5 mt-4">
            <div className="d-flex justify-content-between align-items-center mb-3">
                <h3 className="mb-0">Governance Policies</h3>
                {status && <span className={statusClass}>{status.text}</span>}
            </div>

            <div className="border rounded p-3 mb-4">
                <div className="text-muted small mb-2">
                    Acting as tenant (not authenticated - no login exists in this system yet):
                </div>
                <Row className="g-2 align-items-center">
                    <Col xs="auto">
                        <Form.Select size="sm" value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
                            <option value="">Select a tenant...</option>
                            {tenants.map((t) => (
                                <option key={t.id} value={t.id}>
                                    {t.name} ({t.id})
                                </option>
                            ))}
                        </Form.Select>
                    </Col>
                    <Col xs="auto" className="text-muted small">or</Col>
                    <Col xs="auto">
                        <Form.Control
                            size="sm"
                            placeholder="New tenant name"
                            value={newTenantName}
                            onChange={(e) => setNewTenantName(e.target.value)}
                        />
                    </Col>
                    <Col xs="auto">
                        <Button size="sm" variant="outline-secondary" onClick={handleCreateTenant} disabled={busy || !newTenantName.trim()}>
                            Create tenant
                        </Button>
                    </Col>
                </Row>
            </div>

            {!tenantId && <NoDataAvailable dataType="policies" errorMessage="Select or create a tenant above first." />}

            {tenantId && (
                <>
                    <Row className="g-2 align-items-center mb-3">
                        <Col xs="auto">
                            <Form.Control
                                size="sm"
                                placeholder="New policy name"
                                value={newPolicyName}
                                onChange={(e) => setNewPolicyName(e.target.value)}
                            />
                        </Col>
                        <Col xs="auto">
                            <Button size="sm" variant="primary" onClick={handleCreatePolicy} disabled={busy || !newPolicyName.trim()}>
                                Create policy
                            </Button>
                        </Col>
                    </Row>

                    {loadingPolicies && <ProcessSpinner message="Loading policies..." />}
                    {!loadingPolicies && policiesError && <NoDataAvailable dataType="policies" errorMessage={policiesError} />}
                    {!loadingPolicies && !policiesError && policies.length === 0 && (
                        <NoDataAvailable dataType="policies" errorMessage="This tenant has no policies yet - create one above." />
                    )}

                    {!loadingPolicies && !policiesError && policies.map((policy) => {
                        const versions = versionsByPolicyId[policy.id];
                        const expanded = versions !== undefined;
                        return (
                            <div key={policy.id} className="border rounded mb-3">
                                <div
                                    className="d-flex justify-content-between align-items-center p-2 px-3"
                                    style={{ cursor: "pointer" }}
                                    onClick={() => toggleExpand(policy.id)}
                                >
                                    <div>
                                        <strong>{policy.name}</strong>{" "}
                                        <span className="text-muted small">{policy.id}</span>
                                    </div>
                                    <span className="text-muted small">{expanded ? "▲ hide versions" : "▼ show versions"}</span>
                                </div>

                                {expanded && (
                                    <div className="p-3 border-top">
                                        <Button
                                            size="sm"
                                            variant="outline-primary"
                                            className="mb-3"
                                            onClick={() => handleCreateDraft(policy.id)}
                                            disabled={busy}
                                        >
                                            + New draft version
                                        </Button>

                                        {versions.length === 0 && (
                                            <p className="text-muted small">No versions yet - create a draft to start.</p>
                                        )}

                                        <Table size="sm" hover responsive>
                                            <thead>
                                                <tr>
                                                    <th>Version</th>
                                                    <th>Status</th>
                                                    <th>Rules (first match wins)</th>
                                                    <th />
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {versions.map((v) => (
                                                    <tr key={v.id}>
                                                        <td>v{v.versionNumber}</td>
                                                        <td>
                                                            <Badge bg={STATUS_VARIANT[v.status] || "secondary"}>{v.status}</Badge>
                                                            {v.status === "DRAFT" && (
                                                                <div className="text-muted" style={{ fontSize: "0.75rem" }}>
                                                                    not evaluated yet
                                                                </div>
                                                            )}
                                                        </td>
                                                        <td>
                                                            {v.rules.length === 0 ? (
                                                                <span className="text-muted small">no rules</span>
                                                            ) : (
                                                                <ol className="mb-0 ps-3">
                                                                    {v.rules.map((r) => (
                                                                        <li key={r.id} className="small">
                                                                            {r.field} {r.operator} {r.value} → <strong>{r.effect}</strong>
                                                                        </li>
                                                                    ))}
                                                                </ol>
                                                            )}
                                                            {v.status === "DRAFT" && (
                                                                <Row className="g-1 mt-2 align-items-center">
                                                                    <Col xs="auto">
                                                                        <Form.Control
                                                                            size="sm"
                                                                            style={{ width: 140 }}
                                                                            placeholder="field"
                                                                            value={ruleForm(v.id).field}
                                                                            onChange={(e) => setRuleForm(v.id, { field: e.target.value })}
                                                                        />
                                                                    </Col>
                                                                    <Col xs="auto">
                                                                        <Form.Select
                                                                            size="sm"
                                                                            style={{ width: 80 }}
                                                                            value={ruleForm(v.id).operator}
                                                                            onChange={(e) => setRuleForm(v.id, { operator: e.target.value })}
                                                                        >
                                                                            {OPERATORS.map((op) => (
                                                                                <option key={op} value={op}>{op}</option>
                                                                            ))}
                                                                        </Form.Select>
                                                                    </Col>
                                                                    <Col xs="auto">
                                                                        <Form.Control
                                                                            size="sm"
                                                                            style={{ width: 100 }}
                                                                            placeholder="value"
                                                                            value={ruleForm(v.id).value}
                                                                            onChange={(e) => setRuleForm(v.id, { value: e.target.value })}
                                                                        />
                                                                    </Col>
                                                                    <Col xs="auto">
                                                                        <Form.Select
                                                                            size="sm"
                                                                            style={{ width: 160 }}
                                                                            value={ruleForm(v.id).effect}
                                                                            onChange={(e) => setRuleForm(v.id, { effect: e.target.value })}
                                                                        >
                                                                            {EFFECTS.map((eff) => (
                                                                                <option key={eff} value={eff}>{eff}</option>
                                                                            ))}
                                                                        </Form.Select>
                                                                    </Col>
                                                                    <Col xs="auto">
                                                                        <Button
                                                                            size="sm"
                                                                            variant="outline-secondary"
                                                                            onClick={() => handleAddRule(policy.id, v.id)}
                                                                            disabled={busy}
                                                                        >
                                                                            + Add rule
                                                                        </Button>
                                                                    </Col>
                                                                </Row>
                                                            )}
                                                        </td>
                                                        <td className="text-end align-top">
                                                            {v.status === "DRAFT" && (
                                                                <Button
                                                                    size="sm"
                                                                    variant="success"
                                                                    onClick={() => handleActivate(policy.id, v.id, v.versionNumber)}
                                                                    disabled={busy}
                                                                >
                                                                    Activate
                                                                </Button>
                                                            )}
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </Table>

                                        <div className="border rounded p-2 mt-2">
                                            <div className="text-muted small mb-2">
                                                Test evaluation (always against the ACTIVE version, never a DRAFT):
                                            </div>
                                            <Row className="g-1 align-items-center">
                                                <Col xs="auto">
                                                    <Form.Control
                                                        size="sm"
                                                        style={{ width: 140 }}
                                                        placeholder="action"
                                                        value={evalForm(policy.id).action}
                                                        onChange={(e) => setEvalForm(policy.id, { action: e.target.value })}
                                                    />
                                                </Col>
                                                <Col xs="auto">
                                                    <Form.Control
                                                        size="sm"
                                                        style={{ width: 140 }}
                                                        placeholder="field (e.g. amount)"
                                                        value={evalForm(policy.id).field}
                                                        onChange={(e) => setEvalForm(policy.id, { field: e.target.value })}
                                                    />
                                                </Col>
                                                <Col xs="auto">
                                                    <Form.Control
                                                        size="sm"
                                                        style={{ width: 100 }}
                                                        placeholder="value"
                                                        value={evalForm(policy.id).value}
                                                        onChange={(e) => setEvalForm(policy.id, { value: e.target.value })}
                                                    />
                                                </Col>
                                                <Col xs="auto">
                                                    <Button
                                                        size="sm"
                                                        variant="outline-dark"
                                                        onClick={() => handleEvaluate(policy.id)}
                                                        disabled={busy}
                                                    >
                                                        Evaluate
                                                    </Button>
                                                </Col>
                                            </Row>
                                            {evalResults[policy.id] && (
                                                <div className="small mt-2">
                                                    {evalResults[policy.id].error ? (
                                                        <span className="text-danger">{evalResults[policy.id].error}</span>
                                                    ) : (
                                                        <>
                                                            Decision: <strong>{evalResults[policy.id].decision}</strong>
                                                            {evalResults[policy.id].policyVersionNumber != null && (
                                                                <> (policy version v{evalResults[policy.id].policyVersionNumber})</>
                                                            )}
                                                            <div className="text-muted">{evalResults[policy.id].reason}</div>
                                                        </>
                                                    )}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </>
            )}
        </Container>
    );
};

export default GovernancePoliciesPage;
