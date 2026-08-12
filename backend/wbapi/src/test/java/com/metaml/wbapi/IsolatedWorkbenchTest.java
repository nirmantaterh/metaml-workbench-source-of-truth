package com.metaml.wbapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * A full-context wbapi test that cannot write to the real development data directory.
 *
 * <p>Every workbench store carries its own persist flag and its own path, and each one that gets
 * left at its production default writes into {@code ./data/}. That was found the hard way three
 * separate times - {@code WorkflowEventStore}, then {@code ApprovalStore}, then
 * {@code TenantPolicyStore} - each time because the isolation properties were copied per test class
 * and one store was missed. The properties below are the complete set, declared once, so a new test
 * gets all of them by annotating with this instead of {@code @SpringBootTest} and cannot
 * reintroduce the defect by forgetting a line.
 *
 * <p>Add per-test settings (its own in-memory datasource name, Camunda job-executor timings, and so
 * on) with {@code @TestPropertySource} on the test class - those take precedence over these, so a
 * test can still override any of them deliberately.
 *
 * <p>This does not affect tests that construct a store directly against a {@code @TempDir} to
 * exercise real persistence: those bypass the Spring beans entirely and keep their own controlled
 * file.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = {
        // every persistent store, disabled - keep this list exhaustive
        "workbench.state.persist=false",
        "workbench.workflow.persist=false",
        "workbench.governance.approval.persist=false",
        "workbench.governance.tenant-policy.persist=false",
        // the two stores that write real files rather than toggling a flag
        "workbench.models.directory=./target/test-data/models",
        "workbench.generation.output-directory=./target/test-data/generated-projects",
        // read-only input, not isolation: generation needs the real template to copy from
        "workbench.generation.template-directory=../../templates/camundademo"
})
public @interface IsolatedWorkbenchTest {
}
