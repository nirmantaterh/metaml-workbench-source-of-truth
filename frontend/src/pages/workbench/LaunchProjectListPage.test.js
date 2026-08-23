import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LaunchProjectListPage from "./LaunchProjectListPage";
import { listModelSummaries, getWorkflowState, launchProject } from "../../services/workbench/WorkbenchService";
import { openCockpitUrl } from "../../components/workbench/openCockpitUrl";

jest.mock("../../services/workbench/WorkbenchService", () => ({
    listModelSummaries: jest.fn(),
    getWorkflowState: jest.fn(),
    launchProject: jest.fn(),
}));

jest.mock("../../components/workbench/openCockpitUrl", () => ({
    __esModule: true,
    openCockpitUrl: jest.fn(),
}));

const button = (name) => screen.getByRole("button", { name });

describe("LaunchProjectListPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        global.fetch = jest.fn();
    });

    test("only lists processes that have already been generated, filtering out the rest", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
            { id: "m-2", name: "Never Generated", projectId: 6, projectDisplayName: "Loans" },
        ]);
        getWorkflowState.mockImplementation(async (id) => {
            if (id === "m-1") {
                return { stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } };
            }
            return { stages: { GENERATE: { status: "PENDING" } } };
        });

        render(<LaunchProjectListPage />);

        expect(await screen.findByText("Wire Transfer Review")).toBeInTheDocument();
        expect(screen.queryByText("Never Generated")).not.toBeInTheDocument();
    });

    test("one click launches, auto-pairs proxy + twin, and opens Cockpit - no separate button", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockResolvedValue({ port: 8091, processKey: "wireTransferReview" });
        global.fetch.mockImplementation(async (url) => ({
            ok: true,
            json: async () => (url.includes("/proxy/")
                ? { processInstanceId: "pi-proxy", role: "initiator" }
                : { processInstanceId: "pi-twin", role: "responder" }),
        }));

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        // no "Start Proxy + Twin" button exists anywhere - pairing happens automatically
        expect(screen.queryByRole("button", { name: "Start Proxy + Twin" })).not.toBeInTheDocument();

        userEvent.click(button("Launch"));

        await waitFor(() => expect(launchProject).toHaveBeenCalledWith({ projectId: "gp-1" }));
        expect(await screen.findByText(/Launched on port 8091 \(process "wireTransferReview"\)/)).toBeInTheDocument();

        await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
        expect(global.fetch.mock.calls.map((call) => call[0]).sort()).toEqual([
            expect.stringMatching(/api\/proxy\/start\?businessKey=sync-\d+$/),
            expect.stringMatching(/api\/twin\/start\?businessKey=sync-\d+$/),
        ]);
        expect(await screen.findByText(/proxy \+ twin started/)).toBeInTheDocument();
        expect(screen.getByText("pi-proxy", { exact: false })).toBeInTheDocument();
        expect(screen.getByText("pi-twin", { exact: false })).toBeInTheDocument();
        expect(openCockpitUrl).toHaveBeenCalledWith("http://localhost:8091/camunda/app/cockpit/engine/");
    });

    test("a platform that doesn't support pairing still launches, with a warning instead of a hard failure",
        async () => {
            listModelSummaries.mockResolvedValue([
                { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
            ]);
            getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
            launchProject.mockResolvedValue({ port: 8091, processKey: "wireTransferReview" });
            global.fetch.mockResolvedValue({ ok: false, status: 404 });

            render(<LaunchProjectListPage />);
            await screen.findByText("Wire Transfer Review");

            userEvent.click(button("Launch"));

            expect(await screen.findByText(/Launched on port 8091/)).toBeInTheDocument();
            expect(await screen.findByText(/could not auto-start proxy \+ twin/)).toBeInTheDocument();
            expect(openCockpitUrl).toHaveBeenCalledWith("http://localhost:8091/camunda/app/cockpit/engine/");
        });

    test("a launch failure never attempts to pair", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockRejectedValue(new Error("Boom"));

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));

        expect(await screen.findByText(/Launch failed: Boom/)).toBeInTheDocument();
        expect(global.fetch).not.toHaveBeenCalled();
    });
});
