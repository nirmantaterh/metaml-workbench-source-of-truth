package com.metaml.workbench.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TenantPolicyServiceTest {

    @TempDir
    Path tempDir;

    // event store disabled - these tests are about the service's own logic, not persistence
    // (that's covered separately, see tenantsPoliciesAndVersionsSurviveARealRestart)
    private TenantPolicyService newService() {
        return new TenantPolicyService(new TenantPolicyStore("unused", false));
    }

    @Test
    void createsAndListsTenants() {
        TenantPolicyService service = newService();

        Tenant citibank = service.createTenant("CitiBank");
        service.createTenant("RedCollar");

        assertThat(service.listTenants()).extracting(Tenant::name)
                .containsExactlyInAnyOrder("CitiBank", "RedCollar");
        assertThat(service.getTenant(citibank.id()).name()).isEqualTo("CitiBank");
    }

    @Test
    void aPolicyGoesThroughDraftAddRuleThenActive() {
        TenantPolicyService service = newService();
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Large Payment Approval");

        PolicyVersion draft = service.createDraftVersion(policy.id(), tenant.id());
        assertThat(draft.status()).isEqualTo(PolicyVersionStatus.DRAFT);
        assertThat(draft.versionNumber()).isEqualTo(1);

        PolicyVersion withRule = service.addRule(draft.id(), tenant.id(), "payment.amount", ">", "10000",
                PolicyEffect.REQUIRE_APPROVAL);
        assertThat(withRule.rules()).hasSize(1);

        PolicyVersion active = service.activateVersion(withRule.id(), tenant.id());
        assertThat(active.status()).isEqualTo(PolicyVersionStatus.ACTIVE);
        assertThat(active.activatedAt()).isNotNull();
        assertThat(service.getActiveVersion(policy.id())).contains(active);
    }

    // only one ACTIVE version per policy at a time - activating a new one must retire whatever
    // held that spot before
    @Test
    void activatingANewVersionRetiresThePreviouslyActiveOne() {
        TenantPolicyService service = newService();
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        PolicyVersion v1 = service.createDraftVersion(policy.id(), tenant.id());
        service.activateVersion(v1.id(), tenant.id());

        PolicyVersion v2 = service.createDraftVersion(policy.id(), tenant.id());
        PolicyVersion v2Active = service.activateVersion(v2.id(), tenant.id());

        List<PolicyVersion> versions = service.listVersions(policy.id(), tenant.id());
        PolicyVersion v1AfterRetire = versions.stream().filter(v -> v.id().equals(v1.id())).findFirst().orElseThrow();
        assertThat(v1AfterRetire.status()).isEqualTo(PolicyVersionStatus.RETIRED);
        assertThat(v2Active.status()).isEqualTo(PolicyVersionStatus.ACTIVE);
        assertThat(service.getActiveVersion(policy.id())).contains(v2Active);
    }

    @Test
    void cannotAddARuleToAnAlreadyActiveVersion() {
        TenantPolicyService service = newService();
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        PolicyVersion v1 = service.createDraftVersion(policy.id(), tenant.id());
        service.activateVersion(v1.id(), tenant.id());

        assertThatThrownBy(() -> service.addRule(v1.id(), tenant.id(), "x", ">", "1", PolicyEffect.DENY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not DRAFT");
    }

    @Test
    void cannotActivateTheSameVersionTwice() {
        TenantPolicyService service = newService();
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        PolicyVersion v1 = service.createDraftVersion(policy.id(), tenant.id());
        service.activateVersion(v1.id(), tenant.id());

        assertThatThrownBy(() -> service.activateVersion(v1.id(), tenant.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not DRAFT");
    }

    // the actual tenant-isolation requirement: tenant B must not be able to read, add a rule
    // to, or activate tenant A's policy - not even knowing its real id
    @Test
    void tenantBCannotReadModifyOrActivateTenantAsPolicy() {
        TenantPolicyService service = newService();
        Tenant citibank = service.createTenant("CitiBank");
        Tenant redcollar = service.createTenant("RedCollar");
        Policy citibankPolicy = service.createTenantPolicy(citibank.id(), "Large Payment Approval");
        PolicyVersion draft = service.createDraftVersion(citibankPolicy.id(), citibank.id());

        assertThatThrownBy(() -> service.listVersions(citibankPolicy.id(), redcollar.id()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.addRule(draft.id(), redcollar.id(), "x", ">", "1", PolicyEffect.DENY))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.activateVersion(draft.id(), redcollar.id()))
                .isInstanceOf(NoSuchElementException.class);
        // and RedCollar's own policy list never includes CitiBank's policy
        assertThat(service.listTenantPolicies(redcollar.id())).isEmpty();
    }

    @Test
    void platformPoliciesAreSeparateFromEveryTenantsOwnPolicies() {
        TenantPolicyService service = newService();
        Tenant tenant = service.createTenant("CitiBank");
        Policy platformPolicy = service.createPlatformPolicy("MetaML Safety Invariant");

        assertThat(service.listPlatformPolicies()).extracting(Policy::id).contains(platformPolicy.id());
        assertThat(service.listTenantPolicies(tenant.id())).isEmpty();
        // a tenant id cannot reach a platform policy through the tenant-scoped mutation path
        assertThatThrownBy(() -> service.createDraftVersion(platformPolicy.id(), tenant.id()))
                .isInstanceOf(NoSuchElementException.class);
    }

    // real restart, not just an in-memory instance still running - same convention this
    // codebase already uses elsewhere (WireTransferWalkthroughTest) to prove @PostConstruct
    // restore actually runs, not just that a `new` object happens to still have its data
    @Test
    void tenantsPoliciesAndVersionsSurviveARealRestart() throws Exception {
        String file = tempDir.resolve("tenant-policies.json").toString();
        TenantPolicyService service = new TenantPolicyService(new TenantPolicyStore(file, true));

        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Large Payment Approval");
        PolicyVersion draft = service.createDraftVersion(policy.id(), tenant.id());
        service.addRule(draft.id(), tenant.id(), "payment.amount", ">", "10000", PolicyEffect.REQUIRE_APPROVAL);
        service.activateVersion(draft.id(), tenant.id());

        TenantPolicyService restarted = new TenantPolicyService(new TenantPolicyStore(file, true));
        Method restore = TenantPolicyService.class.getDeclaredMethod("restore");
        restore.setAccessible(true);
        restore.invoke(restarted);

        assertThat(restarted.getTenant(tenant.id()).name()).isEqualTo("CitiBank");
        assertThat(restarted.listTenantPolicies(tenant.id())).extracting(Policy::id).containsExactly(policy.id());
        PolicyVersion active = restarted.getActiveVersion(policy.id()).orElseThrow();
        assertThat(active.status()).isEqualTo(PolicyVersionStatus.ACTIVE);
        assertThat(active.rules()).hasSize(1);
        assertThat(active.rules().get(0).field()).isEqualTo("payment.amount");
    }
}
