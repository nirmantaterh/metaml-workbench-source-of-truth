import React, { useState } from 'react';
import { Modal, Button } from "react-bootstrap";

const RemoveConfirmationModal = ({ show, onHide, onConfirm, itemToRemove }) => {
    return (
        <Modal show={show} onHide={onHide}>
            <Modal.Header closeButton>
                <Modal.Title>Remove Confirmation</Modal.Title>
            </Modal.Header>
            <Modal.Body>
                Are you sure you want to remove {itemToRemove}?
            </Modal.Body>

            <Modal.Footer>
                <Button variant='secondary' onClick={onHide}>
                Cancel
                </Button>{" "}
                <Button variant='danger' onClick={onConfirm}>
                Remove
                </Button>
            </Modal.Footer>
        </Modal>
    );
};

export default RemoveConfirmationModal;