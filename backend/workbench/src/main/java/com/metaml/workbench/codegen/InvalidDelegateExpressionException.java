package com.metaml.workbench.codegen;

/**
 * Reports a BPMN {@code delegateExpression} that names no delegate bean.
 *
 * <p>Carries the BPMN element ID so the UI can locate the exact error.
 * Blank expressions can pass model save but fail only when the task executes,
 * so generation fails earlier. An absent attribute is left to Camunda validation.
 */
public class InvalidDelegateExpressionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    // "" would hide the details panel's "Go to error" button, which only renders when this field
    // is non-blank.
    private static final String BLANK_LABEL = "(blank)";

    private final String bpmnElementId;
    private final String taskName;
    private final String rawExpression;

    public InvalidDelegateExpressionException(String bpmnElementId, String taskName, String rawExpression) {
        super("BPMN element '" + bpmnElementId + "'"
                + (taskName == null || taskName.isBlank() ? "" : " (" + taskName.replaceAll("\\s+", " ").trim() + ")")
                + " declares a delegateExpression of '" + (rawExpression == null ? "" : rawExpression)
                + "', which does not name a delegate bean. Give the task a delegate expression such as"
                + " ${myService}, or remove the attribute.");
        this.bpmnElementId = bpmnElementId;
        this.taskName = taskName;
        this.rawExpression = rawExpression == null || rawExpression.isBlank() ? BLANK_LABEL : rawExpression;
    }

    // Two expressions sanitize to the same class name; only one can be written, so the element
    // named here is the one that loses its bean.
    public static InvalidDelegateExpressionException collision(String bpmnElementId, String taskName,
            String rawExpression, String otherExpression, String className) {
        return new InvalidDelegateExpressionException(bpmnElementId, taskName, rawExpression,
                "BPMN element '" + bpmnElementId + "' declares delegateExpression '" + rawExpression
                        + "', which generates the same delegate class '" + className + "' as '" + otherExpression
                        + "'. Only one of them can exist, so rename one of the two expressions.");
    }

    private InvalidDelegateExpressionException(String bpmnElementId, String taskName, String rawExpression,
            String message) {
        super(message);
        this.bpmnElementId = bpmnElementId;
        this.taskName = taskName;
        this.rawExpression = rawExpression == null || rawExpression.isBlank() ? BLANK_LABEL : rawExpression;
    }

    public String bpmnElementId() {
        return bpmnElementId;
    }

    public String taskName() {
        return taskName;
    }

    public String rawExpression() {
        return rawExpression;
    }
}
