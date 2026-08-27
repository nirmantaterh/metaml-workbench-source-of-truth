package com.metaml.workbench.automation;

import org.camunda.bpm.engine.delegate.DelegateExecution;

// The seam another project plugs into. A project attaching to the workbench writes one bean of this and names it after its project id; TwinAutomationDelegate picks it up out of the Map<String, ProjectAutomationService> Spring assembles from the bean names, so nothing in here has to be edited to add one. The execution handed over is the TWIN's, never the original's. Reading from it is fine - the bridge has already put this activity's evolvedAgent_* and evolvedAgentOutput_* on it by the time this runs. Writing to it is fine too, but write onto the twin only: the original belongs to whoever is clicking through it. execution.getCurrentActivityId() is this automation's OWN service task id, e.g. Task_KYC_automate - not the activity id evolvedAgent_*/evolvedAgentOutput_* are named after, which is the synchronization point (receive task) right before it. Call TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId()) first, the way TwinAutomationDelegate and DefaultProjectAutomationService both do, or every variable lookup keyed on "the activity id" will silently miss.
public interface ProjectAutomationService {

    AutomationResult execute(DelegateExecution execution);
}
