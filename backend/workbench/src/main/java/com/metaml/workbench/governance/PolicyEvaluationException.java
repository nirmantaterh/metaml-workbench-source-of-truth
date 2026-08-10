package com.metaml.workbench.governance;

// Thrown when a rule genuinely can't be evaluated - an operator the engine doesn't know, or a
// value it can't interpret for that operator. Never caught and turned into ALLOW; the caller
// needs to know evaluation failed rather than get a decision that looks real but isn't.
public class PolicyEvaluationException extends RuntimeException {
    public PolicyEvaluationException(String message) {
        super(message);
    }
}
