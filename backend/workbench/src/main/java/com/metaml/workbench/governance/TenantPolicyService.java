package com.metaml.workbench.governance;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Phase 1 of the enterprise governance work (see the Phase 0 architecture audit): tenant
// identity plus a persisted, versioned, tenant-scoped policy store. Deliberately NOT a
// decision engine - nothing here evaluates a PolicyRule against a real request. That is a
// later phase, once there is a real call site ready to consume ALLOW/DENY/REQUIRE_APPROVAL
// (the Phase 0 audit traced exactly where that call site should live, inside
// WorkbenchServiceImpl.runEvolution, after an agent's proposal is known - Phase 1 does not
// touch that method at all).
//
// Trust boundary, stated plainly because the Phase 0 audit was explicit about not glossing
// over it: every tenantId here is caller-supplied. There is no authentication anywhere in
// this backend yet (see WebSecurityConfig's own comment - permitAll(), loopback-only). What
// this class enforces is that one tenant's data can never be reached through another
// tenant's id - real isolation of DATA. It is not proof of WHO is making the call; that
// needs real authentication, which is out of scope for this phase.
@Component
public class TenantPolicyService {

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    private final Map<String, List<PolicyVersion>> versionsByPolicyId = new ConcurrentHashMap<>();
    private final TenantPolicyStore store;
    // guards create/add-rule/activate so two racing calls on the same policy can't both win -
    // see activateVersion's own comment. This is demo-scale single-JVM locking, not
    // distributed locking; the Phase 0 audit was explicit that inventing the latter here
    // would be over-engineering for what this system actually is today.
    private final Object writeLock = new Object();

    public TenantPolicyService(TenantPolicyStore store) {
        this.store = store;
    }

    @PostConstruct
    void restore() {
        TenantPolicyStore.Snapshot snapshot = store.load();
        for (Tenant tenant : snapshot.tenants()) {
            tenants.put(tenant.id(), tenant);
        }
        for (Policy policy : snapshot.policies()) {
            policies.put(policy.id(), policy);
        }
        for (Map.Entry<String, List<PolicyVersion>> entry : snapshot.versionsByPolicyId().entrySet()) {
            versionsByPolicyId.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
        }
    }

    // ---- Tenants ----

    public Tenant createTenant(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank");
        }
        Tenant tenant = new Tenant(UUID.randomUUID().toString(), name.trim(), Instant.now());
        tenants.put(tenant.id(), tenant);
        persist();
        return tenant;
    }

    public List<Tenant> listTenants() {
        return new ArrayList<>(tenants.values());
    }

    public Tenant getTenant(String tenantId) {
        Tenant tenant = tenants.get(tenantId);
        if (tenant == null) {
            throw new NoSuchElementException("Tenant not found: " + tenantId);
        }
        return tenant;
    }

    // ---- Policies ----

    // a policy for a tenant that doesn't exist would be an orphan nothing could ever list -
    // getTenant() throws first, same "fail loudly, not silently" choice made everywhere else
    // in this codebase
    public Policy createTenantPolicy(String tenantId, String name) {
        getTenant(tenantId);
        return createPolicy(tenantId, name);
    }

    public Policy createPlatformPolicy(String name) {
        return createPolicy(null, name);
    }

    private Policy createPolicy(String tenantId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Policy name must not be blank");
        }
        Policy policy = new Policy(UUID.randomUUID().toString(), tenantId, name.trim(), Instant.now());
        policies.put(policy.id(), policy);
        versionsByPolicyId.put(policy.id(), new CopyOnWriteArrayList<>());
        persist();
        return policy;
    }

    // tenant-scoped - only this tenant's own policies, never another tenant's and never
    // platform policies mixed in silently
    public List<Policy> listTenantPolicies(String tenantId) {
        getTenant(tenantId);
        return policies.values().stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .toList();
    }

    public List<Policy> listPlatformPolicies() {
        return policies.values().stream().filter(Policy::isPlatform).toList();
    }

    // ---- Policy versions ----

    public PolicyVersion createDraftVersion(String policyId, String tenantId) {
        synchronized (writeLock) {
            requirePolicyAccessible(policyId, tenantId);
            List<PolicyVersion> versions = versionsByPolicyId.computeIfAbsent(policyId,
                    id -> new CopyOnWriteArrayList<>());
            int nextNumber = versions.stream().mapToInt(PolicyVersion::versionNumber).max().orElse(0) + 1;
            PolicyVersion version = new PolicyVersion(UUID.randomUUID().toString(), policyId, nextNumber,
                    PolicyVersionStatus.DRAFT, new ArrayList<>(), Instant.now(), null);
            versions.add(version);
            persist();
            return version;
        }
    }

    public PolicyVersion addRule(String versionId, String tenantId, String field, String operator, String value,
            PolicyEffect effect) {
        if (field == null || field.isBlank() || operator == null || operator.isBlank() || effect == null) {
            throw new IllegalArgumentException("field, operator and effect must not be blank");
        }
        synchronized (writeLock) {
            PolicyVersion version = requireVersion(versionId);
            requirePolicyAccessible(version.policyId(), tenantId);
            if (version.status() != PolicyVersionStatus.DRAFT) {
                throw new IllegalStateException("Cannot add a rule to version " + version.versionNumber()
                        + " of policy " + version.policyId() + " - it is " + version.status()
                        + ", not DRAFT. Create a new draft version instead.");
            }
            PolicyRule rule = new PolicyRule(UUID.randomUUID().toString(), field, operator, value, effect);
            List<PolicyRule> newRules = new ArrayList<>(version.rules());
            newRules.add(rule);
            PolicyVersion updated = new PolicyVersion(version.id(), version.policyId(), version.versionNumber(),
                    version.status(), newRules, version.createdAt(), version.activatedAt());
            replaceVersion(updated);
            persist();
            return updated;
        }
    }

    public PolicyVersion activateVersion(String versionId, String tenantId) {
        synchronized (writeLock) {
            PolicyVersion version = requireVersion(versionId);
            requirePolicyAccessible(version.policyId(), tenantId);
            if (version.status() != PolicyVersionStatus.DRAFT) {
                // someone else may have already activated this exact version (or retired it) -
                // this is the small optimistic check the Phase 0 audit called for: reject
                // rather than silently re-activate or double-retire
                throw new IllegalStateException("Cannot activate version " + version.versionNumber()
                        + " of policy " + version.policyId() + " - it is " + version.status() + ", not DRAFT");
            }
            List<PolicyVersion> versions = versionsByPolicyId.get(version.policyId());
            List<PolicyVersion> updated = new ArrayList<>();
            for (PolicyVersion v : versions) {
                if (v.id().equals(versionId)) {
                    updated.add(new PolicyVersion(v.id(), v.policyId(), v.versionNumber(),
                            PolicyVersionStatus.ACTIVE, v.rules(), v.createdAt(), Instant.now()));
                } else if (v.status() == PolicyVersionStatus.ACTIVE) {
                    // only one ACTIVE version per policy at a time - retire whoever held that
                    // spot before this activation
                    updated.add(new PolicyVersion(v.id(), v.policyId(), v.versionNumber(),
                            PolicyVersionStatus.RETIRED, v.rules(), v.createdAt(), v.activatedAt()));
                } else {
                    updated.add(v);
                }
            }
            versionsByPolicyId.put(version.policyId(), new CopyOnWriteArrayList<>(updated));
            persist();
            return requireVersion(versionId);
        }
    }

    public List<PolicyVersion> listVersions(String policyId, String tenantId) {
        requirePolicyAccessible(policyId, tenantId);
        return new ArrayList<>(versionsByPolicyId.getOrDefault(policyId, List.of()));
    }

    // deliberately no tenant check here - this is the read path a future decision engine will
    // use, and platform policies (tenantId null) need to be readable too. Reading only
    // requires already knowing the policyId; MUTATING one is what the checks above guard.
    public Optional<PolicyVersion> getActiveVersion(String policyId) {
        return versionsByPolicyId.getOrDefault(policyId, List.of()).stream()
                .filter(v -> v.status() == PolicyVersionStatus.ACTIVE)
                .findFirst();
    }

    // ---- internal helpers ----

    private void requirePolicyAccessible(String policyId, String tenantId) {
        Policy policy = policies.get(policyId);
        boolean ownedByCaller = policy != null && Objects.equals(policy.tenantId(), tenantId);
        // same "not found" message whether the policy doesn't exist or belongs to someone
        // else - a wrong tenantId must never be able to tell those two cases apart, or the
        // distinction itself leaks information across the tenant boundary
        if (policy == null || !ownedByCaller) {
            throw new NoSuchElementException("Policy not found: " + policyId);
        }
    }

    private PolicyVersion requireVersion(String versionId) {
        for (List<PolicyVersion> versions : versionsByPolicyId.values()) {
            for (PolicyVersion v : versions) {
                if (v.id().equals(versionId)) {
                    return v;
                }
            }
        }
        throw new NoSuchElementException("Policy version not found: " + versionId);
    }

    private void replaceVersion(PolicyVersion updated) {
        List<PolicyVersion> versions = versionsByPolicyId.get(updated.policyId());
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).id().equals(updated.id())) {
                versions.set(i, updated);
                return;
            }
        }
    }

    private void persist() {
        store.save(new TenantPolicyStore.Snapshot(new ArrayList<>(tenants.values()),
                new ArrayList<>(policies.values()), new HashMap<>(versionsByPolicyId)));
    }
}
