import React from "react";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import DeployedAppsPage from "./DeployedAppsPage";
import { listRunningProjects, stopProject } from "../../services/workbench/WorkbenchService";

jest.mock("../../services/workbench/WorkbenchService", () => ({
    listRunningProjects: jest.fn(),
    stopProject: jest.fn(),
}));

const RUNNING = {
    projectId: "proj-abc",
    processKey: "order-process",
    port: 8090,
    launchedAt: "2026-08-10T12:00:00Z",
    modelId: "model-1",
};

// modelId is null for anything launched before the current backend session - the in-memory
// modelIdByProjectId map is the only thing that knows the link (see WorkbenchServiceImpl)
const RUNNING_WITHOUT_MODEL = { ...RUNNING, projectId: "proj-old", processKey: "legacy-process", modelId: null };

const renderPage = () => render(
    <MemoryRouter>
        <DeployedAppsPage />
    </MemoryRouter>
);

// the row for a given process, so per-row assertions can't accidentally match another row's button
const rowFor = (processKey) => screen.getByText(processKey).closest("tr");

describe("DeployedAppsPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        stopProject.mockResolvedValue(true);
    });

    test("renders the running-apps list from listRunningProjects()", async () => {
        listRunningProjects.mockResolvedValue([RUNNING]);

        renderPage();

        expect(await screen.findByText("order-process")).toBeInTheDocument();
        const row = rowFor("order-process");
        expect(within(row).getByText("8090")).toBeInTheDocument();
        expect(within(row).getByText("proj-abc")).toBeInTheDocument();
        expect(within(row).getByText("running")).toBeInTheDocument();
    });

    test("shows a nothing-running message when the list is empty", async () => {
        listRunningProjects.mockResolvedValue([]);

        renderPage();

        expect(await screen.findByText(/No deployed applications available/i)).toBeInTheDocument();
        expect(
            screen.getByText(/Nothing is currently running - generate and launch a project first\./i)
        ).toBeInTheDocument();
        expect(screen.queryByRole("table")).not.toBeInTheDocument();
    });

    test("Stop calls stopProject and refreshes the list afterwards", async () => {
        // second load is what actually corrects the display, so it has to return the post-stop list
        listRunningProjects.mockResolvedValueOnce([RUNNING]).mockResolvedValue([]);

        renderPage();
        await screen.findByText("order-process");

        userEvent.click(within(rowFor("order-process")).getByRole("button", { name: "Stop" }));

        await waitFor(() => expect(stopProject).toHaveBeenCalledWith({ projectId: "proj-abc" }));
        await waitFor(() => expect(listRunningProjects).toHaveBeenCalledTimes(2));
        expect(await screen.findByText(/No deployed applications available/i)).toBeInTheDocument();
    });

    // a 404 here means it already stopped on its own between the last refresh and this click -
    // the refresh, not the error, is what the user should end up seeing
    test("Stop still refreshes the list when stopProject fails", async () => {
        listRunningProjects.mockResolvedValueOnce([RUNNING]).mockResolvedValue([]);
        stopProject.mockRejectedValue(new Error("Request failed with status code 404"));

        renderPage();
        await screen.findByText("order-process");

        userEvent.click(within(rowFor("order-process")).getByRole("button", { name: "Stop" }));

        await waitFor(() => expect(listRunningProjects).toHaveBeenCalledTimes(2));
        expect(await screen.findByText(/No deployed applications available/i)).toBeInTheDocument();
    });

    // react-bootstrap's Button renders as={Link} as an <a role="button">, so this is a button by
    // role even though it navigates - querying for role "link" here silently matches nothing
    test("'Evolve this' links back to the model editor when modelId is present", async () => {
        listRunningProjects.mockResolvedValue([RUNNING]);

        renderPage();
        await screen.findByText("order-process");

        const evolve = within(rowFor("order-process")).getByRole("button", { name: "Evolve this" });
        expect(evolve).toHaveAttribute("href", "/wb/model/model-1");
    });

    test("'Evolve this' is replaced by fallback text when modelId is null", async () => {
        listRunningProjects.mockResolvedValue([RUNNING_WITHOUT_MODEL]);

        renderPage();
        await screen.findByText("legacy-process");

        const row = rowFor("legacy-process");
        expect(within(row).queryByRole("button", { name: "Evolve this" })).not.toBeInTheDocument();
        expect(within(row).getByText("(model unknown)")).toBeInTheDocument();
        // still stoppable - only the back-link to its model is lost, not control of the process
        expect(within(row).getByRole("button", { name: "Stop" })).toBeInTheDocument();
    });

    test("only the row with a modelId gets an Evolve link when the list has both", async () => {
        listRunningProjects.mockResolvedValue([RUNNING, RUNNING_WITHOUT_MODEL]);

        renderPage();
        await screen.findByText("order-process");

        expect(screen.getAllByRole("button", { name: "Evolve this" })).toHaveLength(1);
        expect(within(rowFor("order-process")).getByRole("button", { name: "Evolve this" })).toBeInTheDocument();
    });
});
