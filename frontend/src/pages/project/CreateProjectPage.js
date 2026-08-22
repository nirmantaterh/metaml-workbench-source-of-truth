import React, { useState } from "react";
import { Alert, Button, Container, Form } from "react-bootstrap";

import { createProject } from "../../services/workbench/ProjectService";

const CreateProjectPage = () => {
    const [projectName, setProjectName] = useState("");
    const [description, setDescription] = useState("");
    const [saving, setSaving] = useState(false);
    const [message, setMessage] = useState(null);

    const save = async (event) => {
        event.preventDefault();
        setSaving(true);
        setMessage(null);
        try {
            const response = await createProject({ displayName: projectName.trim(), description: description.trim() });
            const project = response.data || response;
            setMessage({ type: "success", text: response.message || `Saved successfully: ${project.displayName || project.name}` });
            setProjectName("");
            setDescription("");
        } catch (error) {
            setMessage({ type: "danger", text: error.response?.data?.message || error.message });
        } finally {
            setSaving(false);
        }
    };

    return (
        <Container className="pt-5 mt-4" style={{ maxWidth: 720 }}>
            <h3>Create Project</h3>
            <p className="text-muted">Register a project before saving its process models.</p>
            {message && <Alert variant={message.type}>{message.text}</Alert>}
            <Form onSubmit={save}>
                <Form.Group className="mb-3" controlId="project-name">
                    <Form.Label>Project Name</Form.Label>
                    <Form.Control value={projectName} onChange={(event) => setProjectName(event.target.value)} required maxLength={255} />
                </Form.Group>
                <Form.Group className="mb-3" controlId="project-description">
                    <Form.Label>Description</Form.Label>
                    <Form.Control as="textarea" rows={4} value={description}
                        onChange={(event) => setDescription(event.target.value)} maxLength={500} />
                </Form.Group>
                <Button type="submit" disabled={saving}>{saving ? "Creating..." : "Create Project"}</Button>
            </Form>
        </Container>
    );
};

export default CreateProjectPage;
