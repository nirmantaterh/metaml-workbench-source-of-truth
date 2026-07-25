import React, { useEffect, useState } from "react";
import { Col, Row, Button, Card, ListGroup, Container } from "react-bootstrap";

const Home = () => {
    return (
        <>
            <section id="home" className="hero-section">
                <div className="container text-center">
                    <h1 className="display-4"><span style={{color: "#1F5F71;"}}>Workbench</span></h1>
                    {/* <p className="lead"><span style={{color: "#1F5F71;"}}>We're revolutionizing the way you work</span></p> */}
                </div>
            </section>
        </>
    );
};

export default Home;