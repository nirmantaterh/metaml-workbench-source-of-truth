import { api } from "../../components/config/api";

export async function getAllProjects() {
    try {
        const result = await api.get(`/projects/all`);
        return result.data;
    } catch (error) {
        throw error;
    }
}

export const listProjects = getAllProjects;

export async function createProject(project) {
    try {
        const result = await api.post(`/projects/create`, project);
        return result.data;
    } catch (error) {
        throw error;
    }
}

export async function getProjectProcesses(projectId) {
    const result = await api.get(`/projects/${projectId}/process-models`);
    return result.data;
}

export async function deleteProject(projectId) {
    const result = await api.delete(`/projects/delete/${projectId}`);
    return result.data;
}
