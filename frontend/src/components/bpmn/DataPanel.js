import React from "react";
import { Button, Form, Table } from "react-bootstrap";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { faPlus, faTrash } from "@fortawesome/free-solid-svg-icons";
import { getBusinessObject } from "bpmn-js/lib/util/ModelUtil";

import {
    canHoldData,
    canRename,
    getDataItems,
    addDataItem,
    updateDataItem,
    removeDataItem,
    getDocumentation,
    setDocumentation,
    setName,
} from "./bpmnUtils";

const DATA_TYPES = ["string", "number", "boolean", "date", "json"];

/**
 * Right-hand editor sidebar for the currently selected BPMN element.
 * Exposes the element name, a free-text description (BPMN documentation)
 * and the MetaML process/task data items (name / type / value rows).
 */
const DataPanel = ({ modeler, element }) => {
    if (!element) {
        return (
            <div className="bpmn-sidebar-empty text-muted">
                Select the process or an activity to edit its data.
            </div>
        );
    }

    const bo = getBusinessObject(element);
    const typeLabel = (bo.$type || "").replace("bpmn:", "");
    const supportsData = canHoldData(element);
    const items = supportsData ? getDataItems(element) : [];
    // bpmn-js can only write a label onto certain element types. The process root -- the
    // default selection on page load -- isn't one of them, so an editable Name box there
    // accepted keystrokes and threw them away. Show it read-only with the reason instead.
    const renameable = canRename(element);

    return (
        <div className="bpmn-sidebar-body">
            <div className="bpmn-sidebar-eyebrow">{typeLabel}</div>

            <Form.Group className="mb-3">
                <Form.Label className="bpmn-field-label">Name</Form.Label>
                <Form.Control
                    size="sm"
                    type="text"
                    value={bo.name || ""}
                    disabled={!renameable}
                    placeholder={renameable ? "" : "Not editable for this element"}
                    onChange={(e) => setName(modeler, element, e.target.value)}
                />
                {!renameable && (
                    <Form.Text className="text-muted">
                        A {typeLabel} has no editable diagram label. Use the "Model name" box in
                        the toolbar to name the model itself.
                    </Form.Text>
                )}
            </Form.Group>

            <Form.Group className="mb-3">
                <Form.Label className="bpmn-field-label">Description</Form.Label>
                <Form.Control
                    size="sm"
                    as="textarea"
                    rows={2}
                    placeholder="Describe this step in plain text…"
                    value={getDocumentation(element)}
                    onChange={(e) => setDocumentation(modeler, element, e.target.value)}
                />
            </Form.Group>

            {supportsData && (
                <div className="bpmn-data-section">
                    <div className="d-flex justify-content-between align-items-center mb-2">
                        <span className="bpmn-field-label mb-0">Data</span>
                        <Button
                            size="sm"
                            variant="outline-primary"
                            onClick={() => addDataItem(modeler, element)}
                        >
                            <FontAwesomeIcon icon={faPlus} /> Add
                        </Button>
                    </div>

                    {items.length === 0 ? (
                        <p className="text-muted small mb-0">
                            No data defined. Add process/task variables the twin can read and write.
                        </p>
                    ) : (
                        <Table size="sm" borderless className="bpmn-data-table mb-0">
                            <thead>
                                <tr>
                                    <th>Name</th>
                                    <th>Type</th>
                                    <th>Value</th>
                                    <th></th>
                                </tr>
                            </thead>
                            <tbody>
                                {items.map((item, idx) => (
                                    <tr key={idx}>
                                        <td>
                                            <Form.Control
                                                size="sm"
                                                type="text"
                                                value={item.name || ""}
                                                onChange={(e) =>
                                                    updateDataItem(modeler, element, item, { name: e.target.value })
                                                }
                                            />
                                        </td>
                                        <td>
                                            <Form.Select
                                                size="sm"
                                                value={item.type || "string"}
                                                onChange={(e) =>
                                                    updateDataItem(modeler, element, item, { type: e.target.value })
                                                }
                                            >
                                                {DATA_TYPES.map((t) => (
                                                    <option key={t} value={t}>
                                                        {t}
                                                    </option>
                                                ))}
                                            </Form.Select>
                                        </td>
                                        <td>
                                            <Form.Control
                                                size="sm"
                                                type="text"
                                                value={item.value || ""}
                                                onChange={(e) =>
                                                    updateDataItem(modeler, element, item, { value: e.target.value })
                                                }
                                            />
                                        </td>
                                        <td className="text-end">
                                            <Button
                                                size="sm"
                                                variant="link"
                                                className="text-danger p-1"
                                                onClick={() => removeDataItem(modeler, element, item)}
                                            >
                                                <FontAwesomeIcon icon={faTrash} />
                                            </Button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </Table>
                    )}
                </div>
            )}
        </div>
    );
};

export default DataPanel;
