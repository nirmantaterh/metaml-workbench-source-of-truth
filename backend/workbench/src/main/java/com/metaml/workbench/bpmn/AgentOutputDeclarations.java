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

// Reads metaml:agentOutputs extension elements to map agent output names to process variable names.
@Component
public class AgentOutputDeclarations {

    private static final Logger logger = LoggerFactory.getLogger(AgentOutputDeclarations.class);

    private static final String METAML_NAMESPACE = "http://metaml.com/schema/bpmn/metaml";
    private static final String DECLARATIONS_ELEMENT = "agentOutputs";
    private static final String DECLARATION_ELEMENT = "agentOutput";

    // lands directly on a running process instance; must be plain camelCase
    private static final Pattern SAFE_VARIABLE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    private final RepositoryService repositoryService;

    public AgentOutputDeclarations(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    // returns output name → declared variable name; empty map for undeclared activities
    public Map<String, String> forActivity(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) {
            return Map.of();
        }

        ModelElementInstance activity;
        try {
            BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
            activity = modelInstance.getModelElementById(activityId);
        } catch (ProcessEngineException e) {
            // missing definition shouldn't fail a task completion
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
