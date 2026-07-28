import React from "react";
import { Button, Form, Table } from "react-bootstrap";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { faPlus, faTrash } from "@fortawesome/free-solid-svg-icons";
import { getBusinessObject } from "bpmn-js/lib/util/ModelUtil";

import {
    canHoldData,
    canBeDefaultFlow,
    isDefaultFlow,
    setDefaultFlow,
    getDataItems,
    addDataItem,
    updateDataItem,
    removeDataItem,
} from "./bpmnUtils";

const DATA_TYPES = ["string", "number", "boolean", "date", "json"];

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
    const defaultFlowCapable = canBeDefaultFlow(element);

    return (
        <div className="bpmn-sidebar-body">
            <div className="bpmn-sidebar-eyebrow">{typeLabel}</div>

            {defaultFlowCapable && (
                <Form.Group className="mb-3">
                    <Form.Check
                        type="checkbox"
                        id="bpmn-default-flow"
                        label="Default flow"
                        checked={isDefaultFlow(element)}
                        onChange={(e) => setDefaultFlow(modeler, element, e.target.checked)}
                    />
                    <Form.Text className="text-muted">
                        This is the flow taken when no other condition matches.
                    </Form.Text>
                </Form.Group>
            )}

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
