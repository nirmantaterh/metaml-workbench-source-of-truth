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
import java.util.List;

// Same atomic-write pattern as TenantPolicyStore right next to it: tmp-file-then-move, rewrite
// the whole file every time, never throw to the caller, an `enabled` flag for tests. A separate
// file from tenant-policies.json for the same reason that one is separate from
// workbench-state.json - approvals are their own concern with their own write frequency
// (resolving one shouldn't rewrite every tenant/policy on disk, and vice versa).
@Component
public class ApprovalStore {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public ApprovalStore(@Value("${workbench.governance.approval.file:./data/approvals.json}") String path,
            @Value("${workbench.governance.approval.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public List<Approval> load() {
        if (!enabled || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            ApprovalDto[] dtos = mapper.readValue(file.toFile(), ApprovalDto[].class);
            List<Approval> approvals = new ArrayList<>();
            for (ApprovalDto dto : dtos) {
                approvals.add(dto.toApproval());
            }
            logger.info("Restored {} approval(s) from {}", approvals.size(), file.toAbsolutePath());
            return approvals;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read approval state from {}, carrying on with nothing restored: {}",
                    file.toAbsolutePath(), e.toString());
            return List.of();
        }
    }

    public void save(List<Approval> approvals) {
        if (!enabled) {
            return;
        }
        synchronized (writeLock) {
            List<ApprovalDto> dtos = new ArrayList<>();
            for (Approval approval : approvals) {
                dtos.add(ApprovalDto.of(approval));
            }
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writeValue(tmp.toFile(), dtos);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not write approval state to {}: {}", file.toAbsolutePath(), e.toString());
            }
        }
    }

    static final class ApprovalDto {
        public String id;
        public String tenantId;
        public String twinId;
        public String activityId;
        public String twinActivityId;
        public Integer loopCounter;
        public String agentType;
        public String action;
        public String policyId;
        public String policyVersionId;
        public Integer policyVersionNumber;
        public String matchedRuleId;
        public String reason;
        public String status;
        public Long createdAtEpochMillis;
        public Long resolvedAtEpochMillis;
        public String resolution;

        static ApprovalDto of(Approval approval) {
            ApprovalDto dto = new ApprovalDto();
            dto.id = approval.id();
            dto.tenantId = approval.tenantId();
            dto.twinId = approval.twinId();
            dto.activityId = approval.activityId();
            dto.twinActivityId = approval.twinActivityId();
            dto.loopCounter = approval.loopCounter();
            dto.agentType = approval.agentType();
            dto.action = approval.action();
            dto.policyId = approval.policyId();
            dto.policyVersionId = approval.policyVersionId();
            dto.policyVersionNumber = approval.policyVersionNumber();
            dto.matchedRuleId = approval.matchedRuleId();
            dto.reason = approval.reason();
            dto.status = approval.status().name();
            dto.createdAtEpochMillis = approval.createdAt() == null ? null : approval.createdAt().toEpochMilli();
            dto.resolvedAtEpochMillis = approval.resolvedAt() == null ? null : approval.resolvedAt().toEpochMilli();
            dto.resolution = approval.resolution();
            return dto;
        }

        Approval toApproval() {
            return new Approval(id, tenantId, twinId, activityId, twinActivityId, loopCounter, agentType, action,
                    policyId, policyVersionId, policyVersionNumber, matchedRuleId, reason,
                    ApprovalStatus.valueOf(status),
                    createdAtEpochMillis == null ? null : Instant.ofEpochMilli(createdAtEpochMillis),
                    resolvedAtEpochMillis == null ? null : Instant.ofEpochMilli(resolvedAtEpochMillis), resolution);
        }
    }
}
