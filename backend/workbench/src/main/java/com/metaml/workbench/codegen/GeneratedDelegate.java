package com.metaml.workbench.codegen;

// One generated Java Delegate class, ready to write to a file. beanName is the unwrapped
// delegateExpression (calculateInterestService, not ${calculateInterestService}) - it's also the
// @Component name the generated class registers under, which is what makes the BPMN's own
// delegateExpression actually resolve to it at runtime. taskName is the human-readable activity
// label the delegateExpression was found on, kept only for a reviewer's benefit (a comment in the
// generated source, a UI label later) - it plays no part in wiring anything.
public record GeneratedDelegate(String beanName, String className, String taskName, String sourceCode) {
}
