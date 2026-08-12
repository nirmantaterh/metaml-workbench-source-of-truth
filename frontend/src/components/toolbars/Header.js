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
                        {/* New scope item 1: just these two now - Sample and the old bare Model
                            link are gone from here (Sample's route still works if linked to
                            directly, it's just not part of this menu anymore) */}
                        <NavDropdown title="Transmute" id="basic-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.CreateProject.path}>
                                Create Project
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.EditProject.path}>
                                Edit Existing Project
                            </NavDropdown.Item>
                        </NavDropdown>
                        {/* Two genuinely different things share this menu now: the pre-existing
                            twin workflow (Connect/Evolve/Bridge, relocated out of the model
                            editor's own toolbar per scope item 1) and New scope item 5's actual
                            "Evolve Workflow" - connecting to and evolving a real deployed
                            generated application, not a twin. */}
                        <NavDropdown title="Evolve" id="evolve-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.EvolvePage.path}>
                                Twin Workflow
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.DeployedAppsPage.path}>
                                Deployed Applications
                            </NavDropdown.Item>
                        </NavDropdown>
                        {/* Phase 6C/this phase: tenant policy lifecycle plus resolving the
                            approvals that policy produces - unrelated to either Evolve entry
                            above, this is what a tenant's policy SAYS and what's pending on it,
                            not any twin's execution */}
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
                    {/* <span class="ml-auto navbar-text"></span> */}
                    <Nav className="justify-content-end">
                        {/* <Nav.Link to={"/"}>About</Nav.Link> */}
                        {/* <Nav.Link to={"/"}>Contact</Nav.Link> */}
                    </Nav>
                </Navbar.Collapse>
            </Container>
        </Navbar>
    );
};

export default Header;