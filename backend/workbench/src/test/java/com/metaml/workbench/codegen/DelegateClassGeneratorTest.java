package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DelegateClassGeneratorTest {

    private final DelegateClassGenerator generator = new DelegateClassGenerator();

    // Joanna's own example: a service task named "Calculate Interest" wired to
    // delegateExpression="${calculateInterestService}" - the class name must come from the
    // expression, not the display name, since that's what Camunda actually looks up at runtime
    @Test
    void generatesAClassNamedAfterTheDelegateExpressionNotTheTaskLabel() {
        String bpmn = loanApprovalBpmn();

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        GeneratedDelegate delegate = generated.get(0);
        assertThat(delegate.beanName()).isEqualTo("calculateInterestService");
        assertThat(delegate.className()).isEqualTo("CalculateInterestService");
        assertThat(delegate.taskName()).isEqualTo("Calculate Interest");
    }

    @Test
    void theGeneratedSourceCompilesAsAJavaDelegateRegisteredUnderTheExpressionsBeanName() {
        List<GeneratedDelegate> generated = generator.generate(loanApprovalBpmn());
        String source = generated.get(0).sourceCode();

        assertThat(source).contains("package com.metaml.generated.delegate;");
        assertThat(source).contains("@Component(\"calculateInterestService\")");
        assertThat(source).contains("public class CalculateInterestService implements JavaDelegate");
        assertThat(source).contains("public void execute(DelegateExecution execution)");
    }

    @Test
    void twoActivitiesSharingOneDelegateExpressionProduceOnlyOneGeneratedClass() {
        String bpmn = twoTasksSameDelegateBpmn();

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).beanName()).isEqualTo("sharedService");
    }

    @Test
    void serviceTasksWithNoDelegateExpressionAreSkippedRatherThanGeneratingEmptyClasses() {
        String bpmn = mixedServiceTasksBpmn();

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).extracting(GeneratedDelegate::beanName).containsExactly("realService");
    }

    @Test
    void aDelegateExpressionWithIllegalJavaIdentifierCharactersGetsSanitizedNotLeftBroken() {
        String bpmn = delegateExpressionBpmn("bad-name.here");

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        // first letter uppercased, illegal chars replaced, still a real compilable identifier
        assertThat(generated.get(0).className()).isEqualTo("Bad_name_here");
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="StartEvent_1" name="Loan Request Received">
                      <bpmn2:outgoing>SequenceFlow_1</bpmn2:outgoing>
                    </bpmn2:startEvent>
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}">
                      <bpmn2:incoming>SequenceFlow_1</bpmn2:incoming>
                      <bpmn2:outgoing>SequenceFlow_2</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="SequenceFlow_1" sourceRef="StartEvent_1" targetRef="ServiceTask_1" />
                    <bpmn2:endEvent id="EndEvent_1" name="Loan Approved">
                      <bpmn2:incoming>SequenceFlow_2</bpmn2:incoming>
                    </bpmn2:endEvent>
                    <bpmn2:sequenceFlow id="SequenceFlow_2" sourceRef="ServiceTask_1" targetRef="EndEvent_1" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String twoTasksSameDelegateBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="First Call"
                        camunda:delegateExpression="${sharedService}" />
                    <bpmn2:serviceTask id="Task_B" name="Second Call"
                        camunda:delegateExpression="${sharedService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String mixedServiceTasksBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="No Delegate Yet" />
                    <bpmn2:serviceTask id="Task_B" name="Real One"
                        camunda:delegateExpression="${realService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String delegateExpressionBpmn(String rawExpression) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="Odd One"
                        camunda:delegateExpression="${%s}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(rawExpression);
    }
}
