import React from "react";
import { Card, Form, Alert } from "react-bootstrap";
import { readTaskProperties, writeAssignee, writeNotes, writeInputData, writeOutputData } from "./camundaProperties";

const readableType = (bpmnType) => {
    if (!bpmnType) return "";
    // e.g. "bpmn:UserTask" -> "User Task"
    const shortType = bpmnType.split(":").pop();
    return shortType.replace(/([a-z])([A-Z])/g, "$1 $2");
};

// camunda:assignee only exists on bpmn:UserTask in Camunda's schema (see
// camundaProperties.js) -- hidden for every other element type via onlyFor below, rather
// than letting the model carry an attribute Camunda's own schema doesn't allow there.
const METADATA_FIELDS = [
    { key: "assignee", label: "Assignee / owner", write: writeAssignee, onlyFor: "bpmn:UserTask" },
    { key: "inputData", label: "Input data", write: writeInputData },
    { key: "outputData", label: "Output data", write: writeOutputData },
    { key: "notes", label: "Notes / custom metadata", as: "textarea", rows: 2, write: writeNotes },
];

const ElementPropertiesPanel = ({ selectedElement, modeler }) => {
    if (!selectedElement) {
        return (
            <Card>
                <Card.Body>
                    <Card.Title>Element properties</Card.Title>
                    <p className="text-muted mb-0">Click an element on the diagram to edit its properties.</p>
                </Card.Body>
            </Card>
        );
    }

    const elementId = selectedElement.id;
    const businessObject = selectedElement.businessObject || {};
    const meta = readTaskProperties(businessObject);
    const fields = METADATA_FIELDS.filter((field) => !field.onlyFor || field.onlyFor === selectedElement.type);

    const handleNameChange = (e) => {
        const modeling = modeler.get("modeling");
        modeling.updateProperties(selectedElement, { name: e.target.value });
    };

    return (
        <Card>
            <Card.Body>
                <Card.Title>Element properties</Card.Title>

                <Form.Group className="mb-2">
                    <Form.Label>Element id</Form.Label>
                    <Form.Control value={elementId} disabled />
                </Form.Group>

                <Form.Group className="mb-2">
                    <Form.Label>Task type</Form.Label>
                    <Form.Control value={readableType(selectedElement.type)} disabled />
                </Form.Group>

                <Form.Group className="mb-2">
                    <Form.Label>Name</Form.Label>
                    <Form.Control
                        defaultValue={businessObject.name || ""}
                        key={elementId}
                        onBlur={handleNameChange}
                        placeholder="Element name"
                    />
                </Form.Group>

                {fields.map(({ key, label, as, rows, write }) => (
                    <Form.Group className="mb-2" key={`${elementId}-${key}`}>
                        <Form.Label>{label}</Form.Label>
                        <Form.Control
                            as={as}
                            rows={rows}
                            defaultValue={meta[key]}
                            onBlur={(e) => write(modeler, selectedElement, e.target.value)}
                        />
                    </Form.Group>
                ))}

                <Alert variant="secondary" className="mb-0 py-2">
                    <small>
                        Every field above is a real BPMN/Camunda property written into the model itself, so it
                        exports and deploys with the diagram instead of living in a separate record.
                    </small>
                </Alert>
            </Card.Body>
        </Card>
    );
};

export default ElementPropertiesPanel;
