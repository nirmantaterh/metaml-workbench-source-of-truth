package com.metaml.workbench.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// keeps the workbench's own models/twins in a json file, since Camunda's own persistence
// doesn't know these maps exist. rewrites the whole file every time - fine at demo scale.
// never throws to the caller: a missing or corrupt file just starts empty instead of
// failing boot.
@Component
public class WorkbenchStateStore {

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchStateStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            // an older file with a field we've since dropped shouldn't blow up the read
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public WorkbenchStateStore(
            @Value("${workbench.state.file:./data/workbench-state.json}") String path,
            @Value("${workbench.state.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public record Snapshot(List<ProcessModel> models, List<TwinProcess> twins) {
        static Snapshot empty() {
            return new Snapshot(List.of(), List.of());
        }
    }

    public Snapshot load() {
        if (!enabled) {
            return Snapshot.empty();
        }
        if (!Files.isRegularFile(file)) {
            logger.info("No workbench state file at {}, starting with no models or twins",
                    file.toAbsolutePath());
            return Snapshot.empty();
        }
        try {
            StateDto dto = mapper.readValue(file.toFile(), StateDto.class);
            List<ProcessModel> models = new ArrayList<>();
            for (ProcessModelDto m : nullToEmpty(dto.models)) {
                models.add(m.toModel());
            }
            List<TwinProcess> twins = new ArrayList<>();
            for (TwinProcessDto t : nullToEmpty(dto.twins)) {
                twins.add(t.toTwin());
            }
            logger.info("Restored {} process model(s) and {} twin(s) from {}",
                    models.size(), twins.size(), file.toAbsolutePath());
            return new Snapshot(models, twins);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read workbench state from {}, carrying on with nothing restored: {}",
                    file.toAbsolutePath(), e.toString());
            return Snapshot.empty();
        }
    }

    public void save(Collection<ProcessModel> models, Collection<TwinProcess> twins) {
        if (!enabled) {
            return;
        }
        // Phase 9/10 red team finding: the DTO snapshot used to be built OUTSIDE this lock, so two
        // concurrent persistState() calls could interleave such that the logically OLDER snapshot
        // won the write lock LAST, silently overwriting a file that a moment earlier correctly held
        // newer data - a pure lost update, reproduced empirically (two threads racing save() with a
        // deliberately older and newer snapshot; the newer one's already-written change vanished).
        // Snapshotting and writing now happen inside the same lock, so one caller's full save()
        // always finishes - snapshot included - before the next one can start theirs.
        synchronized (writeLock) {
            StateDto dto = new StateDto();
            dto.models = new ArrayList<>();
            for (ProcessModel model : models) {
                dto.models.add(ProcessModelDto.of(model));
            }
            dto.twins = new ArrayList<>();
            for (TwinProcess twin : twins) {
                dto.twins.add(TwinProcessDto.of(twin));
            }

            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writeValue(tmp.toFile(), dto);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not write workbench state to {}: {}",
                        file.toAbsolutePath(), e.toString());
            }
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // plain dtos instead of binding straight to the model classes - Jackson would replace
    // TwinProcess's CopyOnWriteArrayList/newKeySet fields with plain ones and silently
    // drop the thread safety the bridge's forwarded-set guard depends on
    static final class StateDto {
        public List<ProcessModelDto> models;
        public List<TwinProcessDto> twins;
    }

    static final class ProcessModelDto {
        public String id;
        public String name;
        public String bpmnXml;
        public Long createdAtEpochMillis;
        public String processDefinitionId;

        static ProcessModelDto of(ProcessModel model) {
            ProcessModelDto dto = new ProcessModelDto();
            dto.id = model.getId();
            dto.name = model.getName();
            dto.bpmnXml = model.getBpmnXml();
            dto.createdAtEpochMillis = model.getCreatedAt() == null
                    ? null
                    : model.getCreatedAt().toEpochMilli();
            dto.processDefinitionId = model.getProcessDefinitionId();
            return dto;
        }

        ProcessModel toModel() {
            return new ProcessModel(id, name, bpmnXml,
                    createdAtEpochMillis == null ? null : Instant.ofEpochMilli(createdAtEpochMillis),
                    processDefinitionId);
        }
    }

    // TwinProcess no longer carries a forwardedBridgeActivities field to leave out here: the bridge
    // dedupe guard now derives straight from the twin's own evolvedAgent_* runtime/history variables
    // (see WorkbenchServiceImpl.alreadyEvolved), which already survive a restart on their own, so
    // there was never anything to persist separately. Governance counters still don't survive a
    // restart, but that isn't protecting a quota that still exists either.
    static final class TwinProcessDto {
        public String id;
        public String modelId;
        public String processDefinitionId;
        public String twinProcessDefinitionId;
        public String originalProcessId;
        public String twinProcessId;
        public String projectId;
        public String status;
        public Long launchedAtEpochMillis;
        public List<String> eventLog;
        public List<ActivityLinkDto> activityLinks;

        static TwinProcessDto of(TwinProcess twin) {
            TwinProcessDto dto = new TwinProcessDto();
            dto.id = twin.getId();
            dto.modelId = twin.getModelId();
            dto.processDefinitionId = twin.getProcessDefinitionId();
            dto.twinProcessDefinitionId = twin.getTwinProcessDefinitionId();
            dto.originalProcessId = twin.getOriginalProcessId();
            dto.twinProcessId = twin.getTwinProcessId();
            dto.projectId = twin.getProjectId();
            dto.status = twin.getStatus();
            dto.launchedAtEpochMillis = twin.getLaunchedAt() == null
                    ? null
                    : twin.getLaunchedAt().toEpochMilli();
            dto.eventLog = new ArrayList<>(twin.getEventLog());
            dto.activityLinks = new ArrayList<>();
            for (ActivityLink link : twin.getActivityLinks()) {
                ActivityLinkDto linkDto = new ActivityLinkDto();
                linkDto.originalActivityId = link.getOriginalActivityId();
                linkDto.twinActivityId = link.getTwinActivityId();
                dto.activityLinks.add(linkDto);
            }
            return dto;
        }

        TwinProcess toTwin() {
            TwinProcess twin = new TwinProcess();
            twin.setId(id);
            twin.setModelId(modelId);
            twin.setProcessDefinitionId(processDefinitionId);
            // Older snapshots predate the dedicated twin definition field. Keep those restorable by
            // falling back to the original definition, which is what older twins were running.
            twin.setTwinProcessDefinitionId(
                    twinProcessDefinitionId == null || twinProcessDefinitionId.isBlank()
                            ? processDefinitionId
                            : twinProcessDefinitionId);
            twin.setOriginalProcessId(originalProcessId);
            twin.setTwinProcessId(twinProcessId);
            // a snapshot written before twins had a project keeps the field's own default
            if (projectId != null && !projectId.isBlank()) {
                twin.setProjectId(projectId);
            }
            twin.setStatus(status);
            twin.setLaunchedAt(launchedAtEpochMillis == null
                    ? null
                    : Instant.ofEpochMilli(launchedAtEpochMillis));
            // add into the collections the constructor already made, don't replace them
            twin.getEventLog().addAll(nullToEmpty(eventLog));
            for (ActivityLinkDto link : nullToEmpty(activityLinks)) {
                twin.getActivityLinks().add(
                        new ActivityLink(link.originalActivityId, link.twinActivityId));
            }
            return twin;
        }
    }

    static final class ActivityLinkDto {
        public String originalActivityId;
        public String twinActivityId;
    }
}
