import React from "react";
import { Modal, Button } from "react-bootstrap";

// Same shape as DeleteConfirmationModal/RemoveConfirmationModal - just parameterized on approve-vs-reject instead of being a second near-identical hardcoded modal, since this page needs both and they only differ in title/verb/button color.
const ApprovalActionConfirmationModal = ({ show, onHide, onConfirm, action, approval }) => {
    const isApprove = action === "approve";
    return (
        <Modal show={show} onHide={onHide}>
            <Modal.Header closeButton>
                <Modal.Title>{isApprove ? "Approve" : "Reject"} Confirmation</Modal.Title>
            </Modal.Header>
            <Modal.Body>
                Are you sure you want to {isApprove ? "approve" : "reject"} this evolution
                ({approval?.action} on twin {approval?.twinId})?
            </Modal.Body>

            <Modal.Footer>
                <Button variant="secondary" onClick={onHide}>
                    Cancel
                </Button>{" "}
                <Button variant={isApprove ? "success" : "danger"} onClick={onConfirm}>
                    {isApprove ? "Approve" : "Reject"}
                </Button>
            </Modal.Footer>
        </Modal>
    );
};

export default ApprovalActionConfirmationModal;
