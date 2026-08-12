import React from "react";
import { render, screen, waitFor, act, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import ModelPage from "./ModelPage";
import {
    saveModel,
    getModel,
    generateDelegates,
    generateProject,
    launchProject,
    getWorkflowState,
} from "../../services/workbench/WorkbenchService";

// name has to start with "mock" to be referenced from a jest.mock factory below
const mockModelXml = "<definitions id=\"test-model\" />";

jest.mock("../../services/workbench/WorkbenchService", () => ({
    saveModel: jest.fn(),
    getModel: jest.fn(),
    generateDelegates: jest.fn(),
    generateProject: jest.fn(),
    launchProject: jest.fn(),
    getWorkflowState: jest.fn(),
}));

// the real hook boots bpmn-js, which ships untransformed ESM and wants a live canvas to attach to -
// neither belongs in a test of this page's save/generate/launch rules. currentXml is the only part
// these tests actually depend on: it's what handleSave sends to the backend.
jest.mock("../../components/bpmn/useBpmnModeler", () => ({
    __esModule: true,
    default: () => ({
        canvasRef: { current: null },
        propertiesPanelRef: { current: null },
        modelerRef: { current: null },
        selected: null,
        selectedActivityId: null,
        importXml: jest.fn().mockResolvedValue(undefined),
        currentXml: jest.fn().mockResolvedValue(mockModelXml),
    }),
}));

// pulls in bpmn-js the same way, and renders nothing without a selected element anyway
jest.mock("../../components/bpmn/DataPanel", () => ({
    __esModule: true,
    default: () => null,
}));

// omitting timestamp keeps WorkflowProgress's title exactly the status string, so a title query
// doesn't depend on the machine's locale date formatting (see its own title logic)
const stage = (status, detail) => (detail ? { status, detail } : { status });

const NOTHING_YET = {
    currentStage: "MODEL",
    stages: { MODEL: stage("PENDING"), GENERATE: stage("PENDING"), LAUNCH: stage("PENDING") },
};

const SAVED = {
    currentStage: "GENERATE",
    stages: {
        MODEL: stage("COMPLETED", "model saved"),
        GENERATE: stage("PENDING"),
        LAUNCH: stage("PENDING"),
    },
};

const GENERATING = {
    currentStage: "GENERATE",
    stages: {
        MODEL: stage("COMPLETED", "model saved"),
        GENERATE: stage("IN_PROGRESS"),
        LAUNCH: stage("PENDING"),
    },
};

const GENERATED = {
    currentStage: "LAUNCH",
    stages: {
        MODEL: stage("COMPLETED", "model saved"),
        GENERATE: stage("COMPLETED", "proj-9"),
        LAUNCH: stage("PENDING"),
    },
};

// the backend's record is a moving target during a run, so the mock reads a variable the test
// advances at the point the real backend would have advanced it - rather than a fixed queue of
// mockResolvedValueOnce values, which would depend on exactly how many times polling happened to
// fire before the assertion
let backendWorkflowState;

const button = (name) => screen.getByRole("button", { name });

const renderPage = () => render(
    <MemoryRouter>
        <ModelPage />
    </MemoryRouter>
);

const saveTheModel = async () => {
    userEvent.click(button("Save"));
    await waitFor(() => expect(saveModel).toHaveBeenCalled());
    await waitFor(() => expect(button("Save")).toBeEnabled());
};

describe("ModelPage - save / generate / launch", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        backendWorkflowState = NOTHING_YET;
        getWorkflowState.mockImplementation(async () => backendWorkflowState);
        getModel.mockResolvedValue({ id: "m-1", name: "New Process", bpmnXml: mockModelXml });
        saveModel.mockResolvedValue({ id: "m-1", name: "New Process" });
        generateDelegates.mockResolvedValue([]);
        generateProject.mockResolvedValue({ projectId: "p-9", processKey: "order-process" });
        launchProject.mockResolvedValue({ port: 8091, processKey: "order-process" });
    });

    describe("handleSave", () => {
        test("sends the current diagram XML and the model name", async () => {
            renderPage();

            await saveTheModel();

            // tenantId is always sent, "" normalized to null - see handleSave's own comment on why
            expect(saveModel).toHaveBeenCalledWith({ name: "New Process", bpmnXml: mockModelXml, tenantId: null });
            expect(await screen.findByText(/Saved model "New Process" \(id m-1\)/)).toBeInTheDocument();
        });

        test("disables Save while the request is in flight, re-enables it after", async () => {
            let resolveSave;
            saveModel.mockReturnValue(new Promise((resolve) => {
                resolveSave = resolve;
            }));

            renderPage();
            userEvent.click(button("Save"));

            await waitFor(() => expect(button("Save")).toBeDisabled());
            // still pending - nothing has come back to enable the next stage yet
            expect(button("Generate")).toBeDisabled();

            backendWorkflowState = SAVED;
            await act(async () => {
                resolveSave({ id: "m-1", name: "New Process" });
            });

            await waitFor(() => expect(button("Save")).toBeEnabled());
        });

        test("reports a failed save instead of advancing the pipeline", async () => {
            saveModel.mockRejectedValue(new Error("Boom"));

            renderPage();
            userEvent.click(button("Save"));

            expect(await screen.findByText(/Save failed: Boom/)).toBeInTheDocument();
            expect(button("Generate")).toBeDisabled();
        });
    });

    describe("handleGenerate", () => {
        test("is disabled until the model has been saved", async () => {
            renderPage();

            const generate = button("Generate");
            expect(generate).toBeDisabled();
            expect(generate).toHaveAttribute("title", "Save the model first");

            userEvent.click(generate);
            expect(generateDelegates).not.toHaveBeenCalled();
            expect(generateProject).not.toHaveBeenCalled();
        });

        test("becomes enabled once a save succeeds, and loses its explanatory title", async () => {
            renderPage();
            backendWorkflowState = SAVED;

            await saveTheModel();

            await waitFor(() => expect(button("Generate")).toBeEnabled());
            expect(button("Generate")).not.toHaveAttribute("title");
        });

        // generateDelegates used to run before generateProject here, but it records no workflow
        // state - a failure raised there aborted the click before the FAILED stage carrying
        // bpmnElementId was ever written, which is exactly what kept "Go to error" from appearing
        // on an element-specific generation failure. generateProject regenerates the delegates
        // itself anyway (against the right package - see doGenerateSpringBootProject), so the
        // preview call was only ever duplicating work outside the recorded path. Removed.
        test("generates the project for the saved model id", async () => {
            renderPage();
            backendWorkflowState = SAVED;
            await saveTheModel();
            await waitFor(() => expect(button("Generate")).toBeEnabled());

            userEvent.click(button("Generate"));

            await waitFor(() => expect(generateProject).toHaveBeenCalledWith({ modelId: "m-1" }));
            expect(generateDelegates).not.toHaveBeenCalled();
            expect(await screen.findByText(/Generated Spring Boot project/)).toBeInTheDocument();
        });
    });

    describe("handleLaunch", () => {
        test("is disabled until a project has been generated", async () => {
            renderPage();
            backendWorkflowState = SAVED;
            await saveTheModel();
            await waitFor(() => expect(button("Generate")).toBeEnabled());

            const launch = button("Launch");
            expect(launch).toBeDisabled();
            expect(launch).toHaveAttribute("title", "Generate a project first");

            userEvent.click(launch);
            expect(launchProject).not.toHaveBeenCalled();
        });

        test("launches the project id that Generate produced", async () => {
            renderPage();
            backendWorkflowState = SAVED;
            await saveTheModel();
            await waitFor(() => expect(button("Generate")).toBeEnabled());

            userEvent.click(button("Generate"));
            await waitFor(() => expect(button("Launch")).toBeEnabled());

            userEvent.click(button("Launch"));

            await waitFor(() => expect(launchProject).toHaveBeenCalledWith({ projectId: "p-9" }));
            expect(await screen.findByText(/Launched on port 8091/)).toBeInTheDocument();
        });

        // the projectId that makes Launch usable is restored from the backend's own record, so
        // reopening an already-generated model doesn't force a pointless second Generate
        test("is enabled from backend state alone when GENERATE already completed", async () => {
            backendWorkflowState = GENERATED;

            renderPage();
            await saveTheModel();

            await waitFor(() => expect(button("Launch")).toBeEnabled());
            expect(generateProject).not.toHaveBeenCalled();

            userEvent.click(button("Launch"));
            await waitFor(() => expect(launchProject).toHaveBeenCalledWith({ projectId: "proj-9" }));
        });
    });

    describe("workflow progress indicator", () => {
        test("starts with every stage pending", async () => {
            renderPage();

            // scoped to the breadcrumb: "Generate" and "Launch" are also button labels in the row
            // above it, so an unscoped text query matches two elements each
            const progress = within(screen.getByRole("navigation", { name: /progress/i }));
            expect(progress.getByText("Model")).toBeInTheDocument();
            expect(progress.getByText("Generate")).toBeInTheDocument();
            expect(progress.getByText("Launch")).toBeInTheDocument();
            // nothing fetched yet - no saved model id to fetch state for
            expect(progress.getAllByTitle("PENDING")).toHaveLength(3);
            expect(getWorkflowState).not.toHaveBeenCalled();
        });

        test("advances as each stage completes, from backend state rather than local clicks", async () => {
            renderPage();
            backendWorkflowState = SAVED;
            await saveTheModel();

            // MODEL done, GENERATE is now the current stage
            await waitFor(() => expect(screen.getByTitle("COMPLETED: model saved")).toBeInTheDocument());

            // a generate that hasn't come back yet: polling picks up IN_PROGRESS on its own, with
            // no second click and nothing inferred from the in-flight request
            let resolveGenerateProject;
            generateProject.mockReturnValue(new Promise((resolve) => {
                resolveGenerateProject = resolve;
            }));
            backendWorkflowState = GENERATING;

            userEvent.click(button("Generate"));

            await waitFor(() => expect(screen.getByTitle("IN_PROGRESS")).toBeInTheDocument());
            expect(screen.getByText("In progress")).toBeInTheDocument();

            backendWorkflowState = GENERATED;
            await act(async () => {
                resolveGenerateProject({ projectId: "p-9", processKey: "order-process" });
            });

            await waitFor(() => expect(screen.getByTitle("COMPLETED: proj-9")).toBeInTheDocument());
            expect(screen.queryByText("In progress")).not.toBeInTheDocument();
        });

        test("View details is disabled until there is workflow state to show", async () => {
            renderPage();

            const viewDetails = screen.getByRole("button", { name: /View details/ });
            expect(viewDetails).toBeDisabled();

            backendWorkflowState = SAVED;
            await saveTheModel();

            await waitFor(() => expect(screen.getByRole("button", { name: /View details/ })).toBeEnabled());
        });
    });
});
