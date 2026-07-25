import React, { useState, useEffect } from "react";
import {
    Route,
    RouterProvider,
    createBrowserRouter,
    createRoutesFromElements
} from "react-router-dom";

import RootLayout from "./components/layout/RootLayout";
import Home from "./pages/Home";
import SamplPage from "./pages/workbench/SamplePage";
import ModelPage from "./pages/workbench/ModelPage";
import ProtectedRoute from "./components/auth/ProtectedRoute";
import { CommonRoutes, WorkbenchRoutes } from "./routes";

const router = createBrowserRouter(
    createRoutesFromElements(
        <>
            <Route path={CommonRoutes.Home.path} element={<RootLayout />}>
                <Route index element={<Home />}/>

                {/* Workbench Routes */}
                <Route path={WorkbenchRoutes.SamplePage.path} element={<SamplPage/>} />
                <Route path={WorkbenchRoutes.ModelPage.path} element={<ModelPage/>} />
            </Route>
        </>
    )
)

export default function Navigation() {
    return (
        <>
            <RouterProvider router={router}/>
        </>
    );
}