import React, { useCallback, useEffect, useState } from "react";
import { Alert, Button, Container, Table } from "react-bootstrap";
import { Link, useParams } from "react-router-dom";

import { getProjectProcesses } from "../../services/workbench/ProjectService";
import { deleteModel } from "../../services/workbench/WorkbenchService";
import { WorkbenchRoutes } from "../../routes";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";
import DeleteConfirmationModal from "../../components/modals/DeleteConfirmationModal";

const ProjectProcessListPage = () => {
    const { projectId } = useParams();
    const [processes, setProcesses] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [selectedForDelete, setSelectedForDelete] = useState(null);
    const [deleting, setDeleting] = useState(false);
    const [deleteError, setDeleteError] = useState(null);
    const load = useCallback(async () => {
        try {
            const response = await getProjectProcesses(projectId);
            setProcesses(response.data || response || []);
            setError(null);
        } catch (err) {
            setError(err.response?.data?.message || err.message);
        } finally {
            setLoading(false);
        }
    }, [projectId]);
    useEffect(() => { load(); }, [load]);

    const remove = async () => {
        if (!selectedForDelete) return;
        setDeleting(true);
        try {
            await deleteModel(selectedForDelete.id);
            setSelectedForDelete(null);
            setDeleteError(null);
            await load();
        } catch (err) {
            setDeleteError(err.response?.data?.message || err.message);
            setSelectedForDelete(null);
        } finally {
            setDeleting(false);
        }
    };

    return (
        <Container className="pt-5 mt-4">
            <div className="d-flex justify-content-between align-items-center mb-3">
                <h3 className="mb-0">Project {projectId}: Processes</h3>
                <Button as={Link} variant="primary" to={WorkbenchRoutes.CreateModel.path}
                    state={{ projectId: Number(projectId) }}>
                    Add process
                </Button>
            </div>
            {deleteError && <Alert variant="warning" dismissible onClose={() => setDeleteError(null)}>{deleteError}</Alert>}
            {loading && <ProcessSpinner message="Loading processes..." />}
            {!loading && error && <Alert variant="danger">{error}</Alert>}
            {!loading && !error && processes.length === 0 && <NoDataAvailable dataType="process models" errorMessage="No process models have been saved in this project." />}
            {!loading && !error && processes.length > 0 && (
                <Table hover responsive>
                    <thead><tr><th>Process ID</th><th>Process name</th><th>Saved</th><th /></tr></thead>
                    <tbody>{processes.map((process) => (
                        <tr key={process.id}><td>{process.id}</td><td>{process.name}</td>
                            <td>{process.createdAt ? new Date(process.createdAt).toLocaleString() : "-"}</td>
                            <td className="text-end"><Button as={Link} size="sm" variant="outline-primary" className="me-2"
                                to={WorkbenchRoutes.ModelEditor.path.replace(":id", process.id)} state={{ projectId: Number(projectId) }}>Edit model</Button>
                                <Button size="sm" variant="outline-danger" disabled={deleting}
                                    onClick={() => setSelectedForDelete(process)}>Delete</Button></td>
                        </tr>
                    ))}</tbody>
                </Table>
            )}
            <DeleteConfirmationModal show={selectedForDelete !== null} onHide={() => setSelectedForDelete(null)}
                onConfirm={remove}
                itemToDelete={selectedForDelete ? `process model "${selectedForDelete.name}" and its generated resources` : ""} />
        </Container>
    );
};

export default ProjectProcessListPage;
