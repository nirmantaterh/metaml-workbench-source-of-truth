import React, { useEffect, useRef, useState } from "react";
import { Col, Row, Button, Card, Container, Form, Alert } from "react-bootstrap";

import BpmnEditor from "../../components/bpmn/BpmnEditor";
import ElementPropertiesPanel from "../../components/bpmn/ElementPropertiesPanel";
import { buildDiagramFromText } from "../../components/bpmn/textToDiagram";
import {
    saveModel,
    getModel,
    launchProcess,
    connectActivity,
    evolveActivity,
    bridgeActivity,
    getGovernancePolicy,
    updateGovernancePolicy,
    getGovernanceUsage,
} from "../../services/workbench/WorkbenchService";

const StepCard = ({ title, children, result, error }) => (
    <Card className="mb-3">
        <Card.Body>
            <Card.Title>{title}</Card.Title>
            <div className="mb-2">{children}</div>
            {error && <Alert variant="danger" className="mb-0">{error}</Alert>}
            {result && (
                <pre className="bg-light p-2 mb-0" style={{ whiteSpace: "pre-wrap" }}>
                    {JSON.stringify(result, null, 2)}
                </pre>
            )}
        </Card.Body>
    </Card>
);

const TransmuteDemoPage = () => {
    const modelerRef = useRef(null);

    const [modelName, setModelName] = useState("Loan Approval");
    const [modelId, setModelId] = useState("");
    const [twinId, setTwinId] = useState("");
    // Both defaults match the real element id in the default BPMN diagram (BpmnEditor's
    // DEFAULT_BPMN_XML) -- original and twin currently start from the same process
    // definition, so both id fields must reference a real, existing activity id.
    const [originalActivityId, setOriginalActivityId] = useState("Activity_Review");
    const [twinActivityId, setTwinActivityId] = useState("Activity_Review");
    const [agentType, setAgentType] = useState("validator");
    // Comma-separated free text, not an array, so the field can be edited directly like the
    // other raw-value form fields on this page -- populated from the server on "View policy",
    // parsed back into an array only when "Update policy" actually submits it.
    const [deniedAgentTypesInput, setDeniedAgentTypesInput] = useState("");
    const [maxEvolutionsInput, setMaxEvolutionsInput] = useState("");
    // Governance policy is shared, server-side, global state (not per-session) -- gates
    // "Update policy" until the real current denylist has actually been loaded into
    // deniedAgentTypesInput at least once, so submitting before that can't send an empty
    // array and silently wipe out whatever another operator configured.
    const [policyLoaded, setPolicyLoaded] = useState(false);

    const [selectedElement, setSelectedElement] = useState(null);
    const [textDescription, setTextDescription] = useState(
        "start -> Review Application -> Approve Loan -> end"
    );

    const [results, setResults] = useState({});
    const [errors, setErrors] = useState({});

    // A failed axios call's err.message is a generic string like "Request failed with
    // status code 400" -- it hides the backend's actual message (e.g. "originalActivityId
    // 'X' does not exist..."), which lives in err.response.data.message. Prefer that.
    const extractErrorMessage = (err) => err.response?.data?.message || err.message;

    const run = async (key, fn) => {
        setErrors((e) => ({ ...e, [key]: null }));
        try {
            const data = await fn();
            setResults((r) => ({ ...r, [key]: data }));
            return data;
        } catch (err) {
            setErrors((e) => ({ ...e, [key]: extractErrorMessage(err) }));
            throw err;
        }
    };

    const handleGenerateFromText = async () => {
        setErrors((e) => ({ ...e, textModel: null }));
        // Clear the selection before rebuilding, not after: buildDiagramFromText destroys
        // the current diagram's elements immediately (via importXML), so any element
        // already selected becomes a stale reference the moment the rebuild starts -- not
        // just if it fails partway through.
        setSelectedElement(null);
        try {
            await buildDiagramFromText(modelerRef.current, textDescription);
        } catch (err) {
            setErrors((e) => ({ ...e, textModel: err.message }));
        }
    };

    const handleSave = async () => {
        try {
            const { xml } = await modelerRef.current.saveXML({ format: true });
            const data = await run("save", () => saveModel(modelName, xml));
            if (data?.data?.id) setModelId(data.data.id);
        } catch (err) {
            setErrors((e) => ({ ...e, save: extractErrorMessage(err) }));
        }
    };

    const handleLoad = async () => {
        // Clear selection before importXML destroys the current diagram's elements --
        // same stale-reference concern as handleGenerateFromText.
        setSelectedElement(null);
        try {
            const data = await run("load", () => getModel(modelId));
            if (data?.data?.bpmnXml) {
                await modelerRef.current.importXML(data.data.bpmnXml);
            }
        } catch (err) {
            setErrors((e) => ({ ...e, load: extractErrorMessage(err) }));
        }
    };

    const handleLaunch = async () => {
        const data = await run("launch", () => launchProcess(modelId));
        if (data?.data?.id) setTwinId(data.data.id);
    };

    const handleConnect = async () => {
        await run("connect", () => connectActivity(twinId, originalActivityId, twinActivityId));
    };

    const handleEvolveAllowed = async () => {
        await run("evolveAllowed", () => evolveActivity(twinId, originalActivityId, agentType));
    };

    const handleEvolveBlocked = async () => {
        await run("evolveBlocked", () => evolveActivity(twinId, originalActivityId, "rogue-agent"));
    };

    const handleBridge = async () => {
        await run("bridge", () => bridgeActivity(twinId, originalActivityId));
    };

    const handleViewPolicy = async () => {
        const data = await run("governancePolicy", () => getGovernancePolicy());
        if (data?.data) {
            setDeniedAgentTypesInput((data.data.deniedAgentTypes || []).join(", "));
            setMaxEvolutionsInput(String(data.data.maxEvolutionsPerTwin ?? ""));
            setPolicyLoaded(true);
        }
    };

    const handleUpdatePolicy = async () => {
        const deniedAgentTypes = deniedAgentTypesInput.split(",").map((s) => s.trim()).filter(Boolean);
        const maxEvolutionsPerTwin = maxEvolutionsInput === "" ? undefined : Number(maxEvolutionsInput);
        await run("governancePolicy", () => updateGovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin));
    };

    const handleViewUsage = async () => {
        await run("governanceUsage", () => getGovernanceUsage(twinId));
    };

    // Load the real current policy once on mount rather than leaving deniedAgentTypesInput
    // empty until someone clicks "View policy" -- see policyLoaded above for why.
    useEffect(() => {
        handleViewPolicy();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <Container fluid className="py-4">
            <h2 className="mb-4">Transmute Demo (Task 1)</h2>

            <StepCard title="1. Model the process (real BPMN editor)" result={results.save} error={errors.save}>
                <Row className="g-2 align-items-center mb-2">
                    <Col md={4}>
                        <Form.Control
                            placeholder="Model name"
                            value={modelName}
                            onChange={(e) => setModelName(e.target.value)}
                        />
                    </Col>
                    <Col md="auto">
                        <Button onClick={handleSave}>Save model (exports real BPMN XML)</Button>
                    </Col>
                </Row>
                <Row className="g-2 align-items-center mb-2">
                    <Col md={8}>
                        <Form.Control
                            placeholder="start -> Review Application -> Approve Loan -> end"
                            value={textDescription}
                            onChange={(e) => setTextDescription(e.target.value)}
                        />
                    </Col>
                    <Col md="auto">
                        <Button variant="outline-primary" onClick={handleGenerateFromText}>
                            Generate diagram from text (replaces canvas)
                        </Button>
                    </Col>
                </Row>
                {errors.textModel && <Alert variant="danger" className="mb-2">{errors.textModel}</Alert>}
                <Row className="g-2">
                    <Col md={8}>
                        <BpmnEditor
                            onModelerReady={(modeler) => { modelerRef.current = modeler; }}
                            onSelectionChanged={setSelectedElement}
                        />
                    </Col>
                    <Col md={4}>
                        <ElementPropertiesPanel
                            selectedElement={selectedElement}
                            modeler={modelerRef.current}
                        />
                    </Col>
                </Row>
            </StepCard>

            <StepCard title="2. Load model" result={results.load} error={errors.load}>
                <Row className="g-2 align-items-center">
                    <Col md={4}>
                        <Form.Control
                            placeholder="Model id"
                            value={modelId}
                            onChange={(e) => setModelId(e.target.value)}
                        />
                    </Col>
                    <Col md="auto">
                        <Button onClick={handleLoad} disabled={!modelId}>Load model (imports XML back into canvas)</Button>
                    </Col>
                </Row>
            </StepCard>

            <StepCard title="3. Launch (create twin)" result={results.launch} error={errors.launch}>
                <Row className="g-2 align-items-center">
                    <Col md="auto">
                        <Button onClick={handleLaunch} disabled={!modelId}>Launch</Button>
                    </Col>
                    <Col md="auto">Twin id: {twinId || "(none yet)"}</Col>
                </Row>
            </StepCard>

            <StepCard title="4. Connect activity to twin activity" result={results.connect} error={errors.connect}>
                <Row className="g-2 align-items-center">
                    <Col md={3}>
                        <Form.Control
                            placeholder="Original activity id"
                            value={originalActivityId}
                            onChange={(e) => setOriginalActivityId(e.target.value)}
                        />
                    </Col>
                    <Col md={3}>
                        <Form.Control
                            placeholder="Twin activity id"
                            value={twinActivityId}
                            onChange={(e) => setTwinActivityId(e.target.value)}
                        />
                    </Col>
                    <Col md="auto">
                        <Button
                            variant="outline-secondary"
                            disabled={!selectedElement}
                            onClick={() => selectedElement && setOriginalActivityId(selectedElement.id)}
                        >
                            Use selected element as original activity id
                        </Button>
                    </Col>
                    <Col md="auto">
                        <Button onClick={handleConnect} disabled={!twinId}>Connect</Button>
                    </Col>
                </Row>
            </StepCard>

            <StepCard title="5. Evolve (request agent + governance)" result={results.evolveAllowed} error={errors.evolveAllowed}>
                <Row className="g-2 align-items-center">
                    <Col md={3}>
                        <Form.Control
                            placeholder="Agent type"
                            value={agentType}
                            onChange={(e) => setAgentType(e.target.value)}
                        />
                    </Col>
                    <Col md="auto">
                        <Button onClick={handleEvolveAllowed} disabled={!twinId}>Evolve (try this agent type)</Button>
                    </Col>
                    <Col md="auto">
                        <Button variant="outline-danger" onClick={handleEvolveBlocked} disabled={!twinId}>
                            Evolve with "rogue-agent" (expect blocked)
                        </Button>
                    </Col>
                </Row>
                {results.evolveBlocked && (
                    <pre className="bg-light p-2 mt-2 mb-0" style={{ whiteSpace: "pre-wrap" }}>
                        {JSON.stringify(results.evolveBlocked, null, 2)}
                    </pre>
                )}
            </StepCard>

            <StepCard title="5b. Bridge (automatic original -> twin forwarding)" result={results.bridge} error={errors.bridge}>
                <Row className="g-2 align-items-center">
                    <Col md="auto">
                        <Button variant="outline-primary" onClick={handleBridge} disabled={!twinId}>
                            Check "{originalActivityId}" in original process and auto-forward to twin
                        </Button>
                    </Col>
                </Row>
                <p className="text-muted mt-2 mb-0">
                    No manual agent type here -- this reads Camunda's own history for the original process
                    instance, and if the linked activity has actually been reached, forwards it through the
                    same governance/node-manager path as a manual evolve, using a default agent type
                    ("validator"). Same twin id and activity id as cards 4-5 above.
                </p>
            </StepCard>

            <StepCard title="6. Governance (constraints on agent use)" result={results.governancePolicy} error={errors.governancePolicy}>
                <Row className="g-2 align-items-center mb-2">
                    <Col md={4}>
                        <Form.Control
                            placeholder="Denied agent types (comma-separated)"
                            value={deniedAgentTypesInput}
                            onChange={(e) => setDeniedAgentTypesInput(e.target.value)}
                        />
                    </Col>
                    <Col md={2}>
                        <Form.Control
                            type="number"
                            min="0"
                            placeholder="Max evolutions/twin"
                            value={maxEvolutionsInput}
                            onChange={(e) => setMaxEvolutionsInput(e.target.value)}
                        />
                    </Col>
                    <Col md="auto">
                        <Button variant="outline-secondary" onClick={handleViewPolicy}>View policy</Button>
                    </Col>
                    <Col md="auto">
                        <Button onClick={handleUpdatePolicy} disabled={!policyLoaded}>Update policy</Button>
                    </Col>
                </Row>
                <Row className="g-2 align-items-center">
                    <Col md="auto">
                        <Button variant="outline-secondary" onClick={handleViewUsage} disabled={!twinId}>
                            View evolution usage for this twin
                        </Button>
                    </Col>
                </Row>
                {results.governanceUsage && (
                    <pre className="bg-light p-2 mt-2 mb-0" style={{ whiteSpace: "pre-wrap" }}>
                        {JSON.stringify(results.governanceUsage, null, 2)}
                    </pre>
                )}
                <p className="text-muted mt-2 mb-0">
                    Two independent gates on evolve: the node manager's catalog (fixed agent types
                    data-enricher/notifier/validator/recommender) says whether an agent type exists at all; governance
                    (above, editable) can still deny an agent type the node manager allows, or block once a twin
                    process hits its evolution quota.
                </p>
            </StepCard>
        </Container>
    );
};

export default TransmuteDemoPage;
