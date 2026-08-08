import React, { useEffect, useState } from "react";
import { Container, Table, Button } from "react-bootstrap";
import { Link } from "react-router-dom";

import { listModels } from "../../services/workbench/WorkbenchService";
import { WorkbenchRoutes } from "../../routes";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

// New scope item 1 (Navigation & UI): "Edit Existing Project" needs something to actually pick
// from, not a box where you paste in an id you already have to know from somewhere else.
const EditProjectListPage = () => {
    const [models, setModels] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const res = await listModels();
                const list = res.data || res;
                if (!cancelled) setModels(Array.isArray(list) ? list : []);
            } catch (err) {
                if (!cancelled) setError(err.response?.data?.message || err.message);
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, []);

    return (
        <Container className="pt-5 mt-4">
            <h3 className="mb-4">Edit Existing Project</h3>
            {loading && <ProcessSpinner message="Loading saved models..." />}
            {!loading && error && <NoDataAvailable dataType="saved models" errorMessage={error} />}
            {!loading && !error && models.length === 0 && (
                <NoDataAvailable dataType="saved models" errorMessage="Nothing saved yet - create a project first." />
            )}
            {!loading && !error && models.length > 0 && (
                <Table hover responsive>
                    <thead>
                        <tr>
                            <th>Name</th>
                            <th>Saved</th>
                            <th>Id</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {models.map((model) => (
                            <tr key={model.id}>
                                <td>{model.name || "Untitled"}</td>
                                <td>{model.createdAt ? new Date(model.createdAt).toLocaleString() : "-"}</td>
                                <td className="text-muted small">{model.id}</td>
                                <td className="text-end">
                                    <Button
                                        as={Link}
                                        to={WorkbenchRoutes.ModelEditor.path.replace(":id", model.id)}
                                        size="sm"
                                        variant="outline-primary"
                                    >
                                        Open
                                    </Button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </Table>
            )}
        </Container>
    );
};

export default EditProjectListPage;
