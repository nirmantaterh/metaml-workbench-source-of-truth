import React, { useState, useEffect } from "react";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { faUser, faHouse } from "@fortawesome/free-solid-svg-icons";
import { Navbar, Container, Nav, NavDropdown, DropdownButton } from "react-bootstrap";
import { Link, useNavigate, useLocation } from "react-router-dom";

import { WorkbenchRoutes } from "../../routes";
import UseMessageAlerts from "../hooks/UseMessageAlerts";
import AlertMessage from "../common/AlertMessage";

const Header = () => {
    const { errorMessage, setErrorMessage, showErrorAlert, setShowErrorAlert } = UseMessageAlerts();
    const navigate = useNavigate();
    const location = useLocation();
    const from = location.state?.from?.pathname || "/";

    return (
        <Navbar expand="lg" sticky='top' className="navbar navbar-light fixed-top py-1 navbar-expand-xl navbar-custom shadow">
            <Container>
                <Navbar.Brand to={"/"} as={Link} className='nav-home' style={{ textDecoration: 'none'}}>
                    {<FontAwesomeIcon icon={faHouse} />}
                </Navbar.Brand>
                <Navbar.Toggle aria-controls='responsive-navbar-nav'/>
                <Navbar.Collapse id="basic-navbar-nav">
                    <Nav className="me-auto">
                        <Nav.Link to={"/"}>Catalog</Nav.Link>
                        <NavDropdown title="Project" id="project-nav-dropdown">
                            <NavDropdown.Item to={"#"}>
                                Create
                            </NavDropdown.Item>
                            <NavDropdown.Item to={"#"}>
                                Edit
                            </NavDropdown.Item>
                            <NavDropdown.Item to={"#"}>
                                Delete
                            </NavDropdown.Item>
                        </NavDropdown>
                        <NavDropdown title="Transmute" id="transmute-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.CreateModel.path}>
                                Model
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.EditModel.path}>
                                Generate
                            </NavDropdown.Item>
                            <NavDropdown.Item to={"#"}>
                                Launch
                            </NavDropdown.Item>
                        </NavDropdown>
                        <NavDropdown title="Evolve" id="evolve-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.EvolvePage.path}>
                                Twin Workflow
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.DeployedAppsPage.path}>
                                Deployed Applications
                            </NavDropdown.Item>
                        </NavDropdown>
                        <NavDropdown title="Governance" id="governance-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.GovernancePolicies.path}>
                                Policies
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.GovernanceApprovals.path}>
                                Approvals
                            </NavDropdown.Item>
                        </NavDropdown>
                        <Nav.Link to={"/"}>Help</Nav.Link>
                    </Nav>
                    <Nav className="justify-content-end">
                    </Nav>
                </Navbar.Collapse>
            </Container>
        </Navbar>
    );
};

export default Header;
