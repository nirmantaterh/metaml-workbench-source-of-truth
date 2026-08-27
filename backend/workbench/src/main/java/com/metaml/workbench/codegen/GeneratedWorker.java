package com.metaml.workbench.codegen;

// One generated external-task worker class, ready to write to a file. topic is the camunda:topic the worker subscribes to - it is what Camunda's external-task fetch-and-lock matches on, so it is the only identity that actually wires the worker to its BPMN task. className is derived from the topic. Parallels GeneratedDelegate, which does the same job for delegateExpression tasks; an external task carries no delegateExpression, so DelegateClassGenerator never sees it and something else has to make it executable.
public record GeneratedWorker(String className, String topic, String sourceCode) {
}
