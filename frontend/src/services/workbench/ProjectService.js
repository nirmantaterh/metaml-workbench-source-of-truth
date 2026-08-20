import { api } from "../../components/config/api";

export async function getAllProjects() {
    try {
        const result = await api.get(`/projects/all`);
        return result.data;
    } catch (error) {
        throw error;
    }
}

export async function createProject(project) {
    try {
        const result = await api.post(`/projects/create`, project);
        return result.data;
    } catch (error) {
        throw error;
    }
}
