package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.codegen.GeneratedDelegate;

class SpringBootProjectGeneratorTest {

    @TempDir
    Path tempDir;

    private Path templateDir;
    private Path outputDir;

    // a small stand-in for the real templates/camundademo project - just enough structure to
    // prove the copy/placeholder-removal/injection logic, not a full 20-file clone of the real
    // template (that's covered by actually running a generated project, not a unit test)
    @BeforeEach
    void buildFakeTemplate() throws IOException {
        templateDir = tempDir.resolve("template");
        outputDir = tempDir.resolve("output");

        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");
        write(templateDir.resolve("src/main/resources/processes/loanApproval.bpmn"), "<bpmn>placeholder demo</bpmn>");
        write(templateDir.resolve("src/main/java/com/example/camundademo/delegates/CalculateInterestService.java"),
                "placeholder delegate");
        write(templateDir.resolve("src/main/java/com/example/camundademo/controller/Camundacontroller.java"),
                "placeholder controller, hardcoded to loanApproval");
        write(templateDir.resolve("src/main/java/com/example/camundademo/context/LoanApplicationContext.java"),
                "placeholder, inert in the real template too");
        write(templateDir.resolve(
                        "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"),
                "placeholder mappings");
        write(templateDir.resolve("src/main/java/com/example/camundademo/security/WebSecurityConfig.java"),
                "unrelated file that must survive the copy untouched");
    }

    private SpringBootProjectGenerator generator() {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString());
    }

    @Test
    void generatesAProjectDirectoryContainingTheRealBpmnNamedAfterItsProcessKey() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(project.processKey()).isEqualTo("loanApproval");
        Path bpmnFile = project.directory().resolve("src/main/resources/processes/loanApproval.bpmn");
        assertThat(bpmnFile).exists();
        assertThat(readString(bpmnFile)).isEqualTo(loanApprovalBpmn());
    }

    @Test
    void removesTheTemplatesOwnPlaceholderDemoContentSoItCantDeployAlongsideTheRealOne() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/delegates/CalculateInterestService.java")).doesNotExist();
        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/controller/Camundacontroller.java")).doesNotExist();
        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/context/LoanApplicationContext.java")).doesNotExist();
        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"))
                .doesNotExist();
    }

    @Test
    void writesAGeneratedControllerBuiltAroundTheActualProcessKeyNotHardcodedToLoanApproval() {
        GeneratedProject project = generator().generate(differentProcessKeyBpmn(), List.of());

        Path controller = project.directory().resolve(
                "src/main/java/com/example/camundademo/controller/GeneratedProcessController.java");
        assertThat(controller).exists();
        String source = readString(controller);
        assertThat(source).contains("startProcessInstanceByKey(\"gradAdmission\")");
        assertThat(source).contains("@PostMapping(\"/start\")");
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/complete-task\")");
    }

    @Test
    void writesEveryGeneratedDelegateClassIntoTheDelegatesPackage() {
        GeneratedDelegate delegate = new GeneratedDelegate("calculateInterestService", "CalculateInterestService",
                "Calculate Interest", com.metaml.workbench.codegen.DelegateKind.SERVICE_TASK,
                "package com.example.camundademo.delegates;\npublic class CalculateInterestService {}");

        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of(delegate));

        Path written = project.directory().resolve(
                "src/main/java/com/example/camundademo/delegates/CalculateInterestService.java");
        assertThat(written).exists();
        assertThat(readString(written)).isEqualTo(delegate.sourceCode());
    }

    @Test
    void unrelatedTemplateFilesSurviveTheCopyUntouched() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        Path securityConfig = project.directory().resolve(
                "src/main/java/com/example/camundademo/security/WebSecurityConfig.java");
        assertThat(securityConfig).exists();
        assertThat(readString(securityConfig)).isEqualTo("unrelated file that must survive the copy untouched");
        assertThat(project.directory().resolve("pom.xml")).exists();
    }

    @Test
    void eachGenerationGetsItsOwnProjectDirectorySoTwoModelsDontCollide() {
        GeneratedProject first = generator().generate(loanApprovalBpmn(), List.of());
        GeneratedProject second = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(first.projectId()).isNotEqualTo(second.projectId());
        assertThat(first.directory()).isNotEqualTo(second.directory());
        assertThat(first.directory()).exists();
        assertThat(second.directory()).exists();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String differentProcessKeyBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_2" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="gradAdmission" name="Grad Admission" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_Review" name="Committee Review" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }
}
