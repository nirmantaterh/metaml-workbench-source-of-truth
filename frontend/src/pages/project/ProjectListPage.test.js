import React from "react";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import ProjectListPage from "./ProjectListPage";
import { listProjects } from "../../services/workbench/ProjectService";

jest.mock("../../services/workbench/ProjectService", () => ({
    listProjects: jest.fn(),
}));

describe("ProjectListPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        listProjects.mockResolvedValue([
            {
                id: 7,
                name: "redcollar_suits",
                displayName: "RedCollar Suits",
                description: "RedCollar manual process for creating custom suits",
            },
        ]);
    });

    test("shows the friendly project label in the list", async () => {
        render(
            <MemoryRouter>
                <ProjectListPage />
            </MemoryRouter>
        );

        expect(await screen.findByText("RedCollar Suits")).toBeInTheDocument();
        expect(screen.queryByText("redcollar_suits")).not.toBeInTheDocument();
    });
});
