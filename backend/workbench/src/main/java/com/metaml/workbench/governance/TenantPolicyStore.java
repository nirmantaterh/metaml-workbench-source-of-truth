package com.metaml.workbench.governance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Same atomic-write pattern as WorkbenchStateStore/WorkflowEventStore: tmp-file-then-move,
// rewrite the whole file every time, never throw to the caller, an `enabled` flag for tests.
// Kept as its own class/file for the same reason those two are separate from each other -
// tenants/policies are a genuinely different shape (and a different Phase 0 concern) from
// either process models or workflow events, and mixing them would make an unrelated change to
// one accidentally risk corrupting the other.
@Component
public class TenantPolicyStore {

    private static final Logger logger = LoggerFactory.getLogger(TenantPolicyStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public TenantPolicyStore(
            @Value("${workbench.governance.tenant-policy.file:./data/tenant-policies.json}") String path,
            @Value("${workbench.governance.tenant-policy.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public record Snapshot(List<Tenant> tenants, List<Policy> policies,
            Map<String, List<PolicyVersion>> versionsByPolicyId) {
        static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of());
        }
    }

    public Snapshot load() {
        if (!enabled || !Files.isRegularFile(file)) {
            return Snapshot.empty();
        }
        try {
            StoreDto dto = mapper.readValue(file.toFile(), StoreDto.class);
            List<Tenant> tenants = new ArrayList<>();
            for (TenantDto t : nullToEmpty(dto.tenants)) {
                tenants.add(t.toTenant());
            }
            List<Policy> policies = new ArrayList<>();
            for (PolicyDto p : nullToEmpty(dto.policies)) {
                policies.add(p.toPolicy());
            }
            Map<String, List<PolicyVersion>> versions = new LinkedHashMap<>();
            for (Map.Entry<String, List<PolicyVersionDto>> entry
                    : nullToEmptyMap(dto.versionsByPolicyId).entrySet()) {
                List<PolicyVersion> list = new ArrayList<>();
                for (PolicyVersionDto v : entry.getValue()) {
                    list.add(v.toVersion());
                }
                versions.put(entry.getKey(), list);
            }
            logger.info("Restored {} tenant(s) and {} polic(ies) from {}",
                    tenants.size(), policies.size(), file.toAbsolutePath());
            return new Snapshot(tenants, policies, versions);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read tenant/policy state from {}, carrying on with nothing restored: {}",
                    file.toAbsolutePath(), e.toString());
            return Snapshot.empty();
        }
    }

    public void save(Snapshot snapshot) {
        if (!enabled) {
            return;
        }
        synchronized (writeLock) {
            StoreDto dto = new StoreDto();
            dto.tenants = new ArrayList<>();
            for (Tenant tenant : snapshot.tenants()) {
                dto.tenants.add(TenantDto.of(tenant));
            }
            dto.policies = new ArrayList<>();
            for (Policy policy : snapshot.policies()) {
                dto.policies.add(PolicyDto.of(policy));
            }
            dto.versionsByPolicyId = new LinkedHashMap<>();
            for (Map.Entry<String, List<PolicyVersion>> entry : snapshot.versionsByPolicyId().entrySet()) {
                List<PolicyVersionDto> versionDtos = new ArrayList<>();
                for (PolicyVersion version : entry.getValue()) {
                    versionDtos.add(PolicyVersionDto.of(version));
                }
                dto.versionsByPolicyId.put(entry.getKey(), versionDtos);
            }

            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writeValue(tmp.toFile(), dto);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not write tenant/policy state to {}: {}", file.toAbsolutePath(), e.toString());
            }
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <K, V> Map<K, V> nullToEmptyMap(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    static final class StoreDto {
        public List<TenantDto> tenants;
        public List<PolicyDto> policies;
        public Map<String, List<PolicyVersionDto>> versionsByPolicyId;
    }

    static final class TenantDto {
        public String id;
        public String name;
        public Long createdAtEpochMillis;

        static TenantDto of(Tenant tenant) {
            TenantDto dto = new TenantDto();
            dto.id = tenant.id();
            dto.name = tenant.name();
            dto.createdAtEpochMillis = tenant.createdAt() == null ? null : tenant.createdAt().toEpochMilli();
            return dto;
        }

        Tenant toTenant() {
            return new Tenant(id, name, createdAtEpochMillis == null ? null : Instant.ofEpochMilli(createdAtEpochMillis));
        }
    }

    static final class PolicyDto {
        public String id;
        public String tenantId;
        public String name;
        public Long createdAtEpochMillis;

        static PolicyDto of(Policy policy) {
            PolicyDto dto = new PolicyDto();
            dto.id = policy.id();
            dto.tenantId = policy.tenantId();
            dto.name = policy.name();
            dto.createdAtEpochMillis = policy.createdAt() == null ? null : policy.createdAt().toEpochMilli();
            return dto;
        }

        Policy toPolicy() {
            return new Policy(id, tenantId, name,
                    createdAtEpochMillis == null ? null : Instant.ofEpochMilli(createdAtEpochMillis));
        }
    }

    static final class PolicyVersionDto {
        public String id;
        public String policyId;
        public int versionNumber;
        public String status;
        public List<PolicyRuleDto> rules;
        public Long createdAtEpochMillis;
        public Long activatedAtEpochMillis;

        static PolicyVersionDto of(PolicyVersion version) {
            PolicyVersionDto dto = new PolicyVersionDto();
            dto.id = version.id();
            dto.policyId = version.policyId();
            dto.versionNumber = version.versionNumber();
            dto.status = version.status().name();
            dto.rules = new ArrayList<>();
            for (PolicyRule rule : version.rules()) {
                dto.rules.add(PolicyRuleDto.of(rule));
            }
            dto.createdAtEpochMillis = version.createdAt() == null ? null : version.createdAt().toEpochMilli();
            dto.activatedAtEpochMillis = version.activatedAt() == null ? null : version.activatedAt().toEpochMilli();
            return dto;
        }

        PolicyVersion toVersion() {
            List<PolicyRule> ruleList = new ArrayList<>();
            for (PolicyRuleDto rule : nullToEmpty(rules)) {
                ruleList.add(rule.toRule());
            }
            return new PolicyVersion(id, policyId, versionNumber, PolicyVersionStatus.valueOf(status), ruleList,
                    createdAtEpochMillis == null ? null : Instant.ofEpochMilli(createdAtEpochMillis),
                    activatedAtEpochMillis == null ? null : Instant.ofEpochMilli(activatedAtEpochMillis));
        }
    }

    static final class PolicyRuleDto {
        public String id;
        public String field;
        public String operator;
        public String value;
        public String effect;

        static PolicyRuleDto of(PolicyRule rule) {
            PolicyRuleDto dto = new PolicyRuleDto();
            dto.id = rule.id();
            dto.field = rule.field();
            dto.operator = rule.operator();
            dto.value = rule.value();
            dto.effect = rule.effect().name();
            return dto;
        }

        PolicyRule toRule() {
            return new PolicyRule(id, field, operator, value, PolicyEffect.valueOf(effect));
        }
    }
}
