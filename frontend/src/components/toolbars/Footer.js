import React from "react";
import { Link } from "react-router-dom";

const Footer = () => {
    return (
        <footer className='page-footer fixed-bottom py-0'>
            <div className="small-print fixed-bottom">
                <div className="container">
                    <span className="pull-left pb-2">
                        <small>
                            <span>&copy; {new Date().getFullYear()} MetaML</span>
                            <span> | </span>
                            <Link to="/#" style={{ textDecoration: "none" }}>Legal</Link>
                        </small>
                    </span>
                </div>
            </div>
        </footer>
    );
}

export default Footer;