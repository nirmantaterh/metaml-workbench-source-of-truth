package com.metaml.workbench.codegen;

// BPMN has two unrelated places a delegateExpression can live, and Camunda expects a different
// Java interface at each one - a class doesn't get to just "implement delegateExpression", the
// generator has to know which shape to emit. SERVICE_TASK is the task's own execution
// (JavaDelegate.execute(DelegateExecution) - the class runs AS the task). TASK_LISTENER is a
// listener bolted onto a user task's lifecycle (TaskListener.notify(DelegateTask) - the class runs
// AROUND a human task, it doesn't replace it). This repo's own demo models only use the second
// form (a userTask's taskListener, event="complete", for agentExecutionDelegate) - the generator
// used to only understand the first, which is why generating a project from those specific demo
// models produced zero delegates and deployed a project that would crash on the first task.
public enum DelegateKind {
    SERVICE_TASK,
    TASK_LISTENER,
    JAVA_CLASS
}
