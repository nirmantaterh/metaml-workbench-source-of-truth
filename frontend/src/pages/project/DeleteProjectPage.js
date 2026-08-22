import React, { useCallback, useEffect, useState } from "react";
import { Alert, Button, Container, Table } from "react-bootstrap";

import { deleteProject, listProjects } from "../../services/workbench/ProjectService";
import DeleteConfirmationModal from "../../components/modals/DeleteConfirmationModal";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

const DeleteProjectPage = () => {
    const [projects, setProjects] = useState([]);
    const [selected, setSelected] = useState(null);
    const [loading, setLoading] = useState(true);
    const [deleting, setDeleting] = useState(false);
    const [message, setMessage] = useState(null);
    const load = useCallback(async () => {
        try {
            const response = await listProjects();
            setProjects(response.data || response || []);
        } catch (err) {
            setMessage({ type: "danger", text: err.response?.data?.message || err.message });
        } finally { setLoading(false); }
    }, []);
    useEffect(() => { load(); }, [load]);
    const remove = async () => {
        if (!selected) return;
        setDeleting(true);
        try {
            const response = await deleteProject(selected.id);
            setMessage({ type: "success", text: response.message || `Deleted ${selected.name}.` });
            setSelected(null);
            await load();
        } catch (err) {
            setMessage({ type: "danger", text: err.response?.data?.message || err.message });
            setSelected(null);
        } finally { setDeleting(false); }
    };
    return (
        <Container className="pt-5 mt-4">
            <h3>Delete Project</h3>
            <p className="text-muted">Deleting a project also deletes its process models and generated resources. Stop running applications first.</p>
            {message && <Alert variant={message.type}>{message.text}</Alert>}
            {loading && <ProcessSpinner message="Loading projects..." />}
            {!loading && projects.length === 0 && <NoDataAvailable dataType="projects" errorMessage="There are no projects to delete." />}
            {!loading && projects.length > 0 && <Table hover responsive><thead><tr><th>ID</th><th>Name</th><th>Display name</th><th /></tr></thead>
                <tbody>{projects.map((project) => <tr key={project.id}><td>{project.id}</td><td>{project.name}</td><td>{project.displayName}</td>
                    <td className="text-end"><Button variant="outline-danger" size="sm" onClick={() => setSelected(project)} disabled={deleting}>Delete</Button></td></tr>)}</tbody>
            </Table>}
            <DeleteConfirmationModal show={selected !== null} onHide={() => setSelected(null)} onConfirm={remove}
                itemToDelete={selected ? `project "${selected.name}" and all of its process models` : ""} />
        </Container>
    );
};

export default DeleteProjectPage;
