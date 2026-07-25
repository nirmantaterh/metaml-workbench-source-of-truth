import React from "react";
import { Outlet } from "react-router-dom";
import Header from "../toolbars/Header";
import Footer from "../toolbars/Footer";

const RootLayout = () => {
    return (
        <main>
            <Header />
            <div>
                <Outlet />
            </div>
            <Footer />
        </main>
    );
}

export default RootLayout;