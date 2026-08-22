import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import CreateProjectPage from "./CreateProjectPage";
import { createProject } from "../../services/workbench/ProjectService";

jest.mock("../../services/workbench/ProjectService", () => ({
    createProject: jest.fn(),
}));

describe("CreateProjectPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        createProject.mockResolvedValue({
            data: {
                id: 7,
                name: "redcollar_suits",
                displayName: "RedCollar Suits",
                description: "RedCollar manual process for creating custom suits",
            },
        });
    });

    test("submits one human-friendly project name and shows the saved label", async () => {
        render(<CreateProjectPage />);

        await userEvent.type(screen.getByLabelText("Project Name"), "RedCollar Suits");
        await userEvent.type(
            screen.getByLabelText("Description"),
            "RedCollar manual process for creating custom suits"
        );

        await userEvent.click(screen.getByRole("button", { name: "Create Project" }));

        await waitFor(() =>
            expect(createProject).toHaveBeenCalledWith({
                displayName: "RedCollar Suits",
                description: "RedCollar manual process for creating custom suits",
            })
        );
        expect(await screen.findByText("Saved successfully: RedCollar Suits")).toBeInTheDocument();
    });
});
