package com.metaml.workbench.codegen;

/**
 * One BPMN element declares a delegateExpression that cannot name a delegate bean.
 *
 * <p>This is the one generation failure that is genuinely attributable to a single BPMN element,
 * which is what lets the workflow error carry a real bpmnElementId and the editor's "Go to error"
 * select the offending task. Everything else that can go wrong during generation (no process
 * element, missing template directory, a failed file write for reasons of its own) is a failure of
 * the operation as a whole and stays generic - see WorkbenchServiceImpl.generateErrorFrom.
 *
 * <p>Why this is a real defect rather than a tolerable oddity: Camunda accepts
 * {@code camunda:delegateExpression=""} at deploy time, so the model saves cleanly. MetaML then
 * skipped it, generated no delegate class for that task, and produced a project that compiles and
 * launches but fails at runtime the moment the token reaches the task, because the bean the BPMN
 * refers to was never written. Failing at generation, pointing at the task, is the honest outcome.
 *
 * <p>Deliberately NOT thrown when the attribute is absent entirely: that is a different model
 * (Camunda rejects it at save time for having no implementation), and the generator's existing
 * behaviour of skipping such a task is relied on elsewhere.
 */
public class InvalidDelegateExpressionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    // shown as the "Delegate" field in the workflow details panel. The raw attribute text, except
    // when that text is empty - the panel only renders "Go to error" when it has something to
    // print there, and "" would hide the very button this exception exists to surface.
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

    // second entry point: two distinct delegateExpressions that sanitize to the same Java class
    // name. Only one class can be written, so the other element's bean silently never exists at
    // runtime - the element named here is the one that loses.
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
