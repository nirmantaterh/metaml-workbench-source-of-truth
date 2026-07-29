package com.metaml.workbench.bpmn;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

// The first thing on the backend to read a metaml: element. Until now the whole extension was the
// modeller's business and travelled through save/deploy as inert XML.
//
// What it reads: <metaml:agentOutputs><metaml:agentOutput name="riskFlagged"
// variable="agentFlaggedRisk" /></metaml:agentOutputs> hanging off an activity's
// extensionElements. The name is one of the outputs the evolved agent reported; the variable is
// what the model author wants it published as alongside the generic agentOutput_* name.
//
// Nothing registers the metaml namespace with the Camunda model API, so the extension elements
// come back as plain ModelElementInstances rather than typed ones. That's fine and it's why this
// drops to the DOM for the last step instead of parsing ProcessModel.getBpmnXml() over again:
// getBpmnModelInstance tolerates the unknown namespace, hands the elements over generically, and
// the engine's deployment cache means the lookup isn't re-reading the XML on every task
// completion. Registering a proper moddle extension would be the tidier version of this, but it
// buys typed getters for two string attributes and nothing else.
@Component
public class AgentOutputDeclarations {

    private static final Logger logger = LoggerFactory.getLogger(AgentOutputDeclarations.class);

    private static final String METAML_NAMESPACE = "http://metaml.com/schema/bpmn/metaml";
    private static final String DECLARATIONS_ELEMENT = "agentOutputs";
    private static final String DECLARATION_ELEMENT = "agentOutput";

    // same shape NodeManagerClient holds its output names to. A variable is going straight onto a
    // running process instance, so it gets the same treatment as a name coming off the wire.
    private static final Pattern SAFE_VARIABLE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    private final RepositoryService repositoryService;

    public AgentOutputDeclarations(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    // output name -> the variable the author wants it published under. Empty for the usual case
    // of an activity that declared nothing.
    public Map<String, String> forActivity(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) {
            return Map.of();
        }

        ModelElementInstance activity;
        try {
            BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
            activity = modelInstance.getModelElementById(activityId);
        } catch (ProcessEngineException e) {
            // a declaration is an extra, so a definition that has gone missing shouldn't be the
            // thing that fails a task completion
            logger.warn("Could not read process definition {} for output declarations on {}: {}",
                    processDefinitionId, activityId, e.getMessage());
            return Map.of();
        }
        if (!(activity instanceof BaseElement element)) {
            return Map.of();
        }
        ExtensionElements extensionElements = element.getExtensionElements();
        if (extensionElements == null) {
            return Map.of();
        }

        Map<String, String> declared = new LinkedHashMap<>();
        for (ModelElementInstance child : extensionElements.getElements()) {
            if (!isMetaml(child.getDomElement(), DECLARATIONS_ELEMENT)) {
                continue;
            }
            for (DomElement declaration : child.getDomElement().getChildElements()) {
                if (!isMetaml(declaration, DECLARATION_ELEMENT)) {
                    continue;
                }
                String name = declaration.getAttribute("name");
                String variable = declaration.getAttribute("variable");
                // a half-filled row is what the modeller's Add button leaves behind before anyone
                // types in it, so it reaches here often enough to be worth ignoring quietly
                if (isBlank(name) || isBlank(variable)) {
                    continue;
                }
                String trimmedVariable = variable.trim();
                if (!SAFE_VARIABLE_NAME.matcher(trimmedVariable).matches()) {
                    logger.warn("Ignoring agent output declaration '{}' -> '{}': variable name isn't "
                            + "plain camelCase", name.trim(), trimmedVariable);
                    continue;
                }
                declared.put(name.trim(), trimmedVariable);
            }
        }
        return declared;
    }

    private static boolean isMetaml(DomElement element, String localName) {
        return METAML_NAMESPACE.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
