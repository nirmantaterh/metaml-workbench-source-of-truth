import React, { useCallback, useEffect, useState } from "react";
import { Alert, Button, Container, Table } from "react-bootstrap";
import { Link } from "react-router-dom";

import { listProjects } from "../../services/workbench/ProjectService";
import { WorkbenchRoutes } from "../../routes";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

const ProjectListPage = () => {
    const [projects, setProjects] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const load = useCallback(async () => {
        try {
            const response = await listProjects();
            setProjects(response.data || response || []);
            setError(null);
        } catch (err) {
            setError(err.response?.data?.message || err.message);
        } finally {
            setLoading(false);
        }
    }, []);
    useEffect(() => { load(); }, [load]);

    return (
        <Container className="pt-5 mt-4">
            <h3>Projects</h3>
            {loading && <ProcessSpinner message="Loading projects..." />}
            {!loading && error && <Alert variant="danger">{error}</Alert>}
            {!loading && !error && projects.length === 0 && <NoDataAvailable dataType="projects" errorMessage="Create a project to get started." />}
            {!loading && !error && projects.length > 0 && (
                <Table hover responsive>
                    <thead><tr><th>Project ID</th><th>Project name</th><th>Display name</th><th>Description</th><th /></tr></thead>
                    <tbody>{projects.map((project) => (
                        <tr key={project.id}>
                            <td>{project.id}</td><td>{project.name}</td><td>{project.displayName}</td><td>{project.description || "-"}</td>
                            <td className="text-end"><Button as={Link} size="sm" variant="outline-primary"
                                to={WorkbenchRoutes.ProjectProcesses.path.replace(":projectId", project.id)}>View processes</Button></td>
                        </tr>
                    ))}</tbody>
                </Table>
            )}
        </Container>
    );
};

export default ProjectListPage;
