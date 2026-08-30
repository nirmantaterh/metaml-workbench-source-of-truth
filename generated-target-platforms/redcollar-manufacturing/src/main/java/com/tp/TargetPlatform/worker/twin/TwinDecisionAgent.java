package com.tp.TargetPlatform.worker.twin;

import java.util.Map;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;

// Pluggable decision boundary for every Twin worker. With no implementation registered, the generated worker falls back to synthetic, non-deterministic output so the twin process can still run standalone (see the worker's own execute() - it injects this via ObjectProvider, not directly, precisely so zero implementations is a supported, non-fatal case). To have the twin mirror real business intelligence - e.g. a risk-scoring model that predicts what the real (proxy) process would decide - register your own @Component implementing this interface; every generated Twin worker starts calling it instead, with no generated code to change.
public interface TwinDecisionAgent {

    // topic: the external-task topic being completed (e.g. "VerifyOrderTwin") - lets one implementation branch on which BPMN activity it's deciding for. task: the locked external task itself, for id/businessKey/variable access. Returns the process variables to complete the task with. If this topic feeds an exclusive gateway's condition, include that variable in the result if you can - the calling worker fills in any the agent leaves out with a non-deterministic fallback so the process never throws PropertyNotFoundException, but a real implementation should be the one deciding it.
    Map<String, Object> decide(String topic, LockedExternalTask task);
}
