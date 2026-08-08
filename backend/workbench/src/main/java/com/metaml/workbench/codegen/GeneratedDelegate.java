package com.metaml.workbench.codegen;

// One generated Java Delegate (or TaskListener) class, ready to write to a file. beanName is the
// unwrapped delegateExpression (calculateInterestService, not ${calculateInterestService}) - it's
// also the @Component name the generated class registers under, which is what makes the BPMN's own
// delegateExpression actually resolve to it at runtime. taskName is the human-readable activity
// label the delegateExpression was found on, kept only for a reviewer's benefit (a comment in the
// generated source, a UI label later) - it plays no part in wiring anything. kind decides which
// interface the generated class implements - see DelegateKind's own comment for why that isn't a
// choice the generator gets to skip.
//
// bpmnElementId (Phase 3B) is the id of the BPMN element that produced this delegate - the start
// of the chain a future "go to error" needs: generated delegate -> delegateExpression -> BPMN
// element id -> source BPMN model. Null whenever that chain isn't actually reliable, which is
// exactly the case DelegateClassGenerator dedups on: if more than one BPMN element points at the
// same delegateExpression, this bean is genuinely shared, and picking one of those elements as
// "the" source would be a fabricated relationship, not a preserved one - see that class's own
// comment on how it decides.
public record GeneratedDelegate(String beanName, String className, String taskName, DelegateKind kind,
        String sourceCode, String bpmnElementId) {

    // pre-Phase-3B call sites (including existing tests) construct a GeneratedDelegate with no
    // notion of a BPMN element id - keeps every one of them compiling and behaving exactly as
    // before rather than forcing a mechanical `, null` onto each of them
    public GeneratedDelegate(String beanName, String className, String taskName, DelegateKind kind,
            String sourceCode) {
        this(beanName, className, taskName, kind, sourceCode, null);
    }
}
