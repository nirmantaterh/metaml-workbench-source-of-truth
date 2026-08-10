import { api } from "../../components/config/api";
import {
    generateProject,
    launchProject,
    listRunningProjects,
    stopProject,
} from "./WorkbenchService";

// the axios instance is the seam here, not the network - these tests pin down the contract this
// module owns: which endpoint each call hits, what payload shape goes with it, and the fact that
// callers get result.data back rather than the whole axios response
jest.mock("../../components/config/api", () => ({
    api: { get: jest.fn(), post: jest.fn() },
}));

describe("WorkbenchService - generated project pipeline", () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    test("generateProject posts the modelId to /wb/transmute/generate-project", async () => {
        api.post.mockResolvedValue({ data: { projectId: "p-1", processKey: "order-process" } });

        const result = await generateProject({ modelId: "m-1" });

        expect(api.post).toHaveBeenCalledWith("/wb/transmute/generate-project", { modelId: "m-1" });
        expect(result).toEqual({ projectId: "p-1", processKey: "order-process" });
    });

    test("launchProject posts the projectId to /wb/transmute/launch-project", async () => {
        api.post.mockResolvedValue({ data: { projectId: "p-1", port: 8090 } });

        const result = await launchProject({ projectId: "p-1" });

        expect(api.post).toHaveBeenCalledWith("/wb/transmute/launch-project", { projectId: "p-1" });
        expect(result).toEqual({ projectId: "p-1", port: 8090 });
    });

    test("stopProject posts the projectId to /wb/transmute/stop-project", async () => {
        api.post.mockResolvedValue({ data: true });

        const result = await stopProject({ projectId: "p-1" });

        expect(api.post).toHaveBeenCalledWith("/wb/transmute/stop-project", { projectId: "p-1" });
        expect(result).toBe(true);
    });

    test("listRunningProjects GETs /wb/transmute/running-projects with no payload", async () => {
        const running = [{ projectId: "p-1", processKey: "order-process", port: 8090, modelId: "m-1" }];
        api.get.mockResolvedValue({ data: running });

        const result = await listRunningProjects();

        expect(api.get).toHaveBeenCalledWith("/wb/transmute/running-projects");
        expect(api.post).not.toHaveBeenCalled();
        expect(result).toEqual(running);
    });

    // the pages render straight off these results, so a rejection has to stay a rejection rather
    // than resolving to undefined - that's what lets their catch blocks show a real message
    test("a failing call rejects rather than swallowing the error", async () => {
        api.post.mockRejectedValue(new Error("Network Error"));

        await expect(launchProject({ projectId: "p-1" })).rejects.toThrow("Network Error");
    });
});
