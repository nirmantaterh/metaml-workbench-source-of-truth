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

/**
 * Keeps the workbench's own models and twins in a json file next to the h2 db, because Camunda
 * persisting its engine state doesn't help us - the maps in WorkbenchServiceImpl are ours and the
 * engine knows nothing about them. Whole thing is rewritten on every change; there are a handful
 * of twins in a demo, not thousands, so that's cheaper than working out what changed.
 *
 * <p>Nothing in here is allowed to throw at the caller. A state file that's missing, truncated or
 * from an older shape of the model has to degrade to "start empty" - losing yesterday's twins is
 * annoying, a backend that won't boot ten minutes before a demo is not.
 */
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
        StateDto dto = new StateDto();
        dto.models = new ArrayList<>();
        for (ProcessModel model : models) {
            dto.models.add(ProcessModelDto.of(model));
        }
        dto.twins = new ArrayList<>();
        for (TwinProcess twin : twins) {
            dto.twins.add(TwinProcessDto.of(twin));
        }

        // one writer at a time, and via a temp file - a half-written snapshot that then fails to
        // parse would silently cost every twin on the next boot
        synchronized (writeLock) {
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

    // Plain dtos rather than serialising the model classes straight. TwinProcess initialises its
    // collections to CopyOnWriteArrayList / newKeySet and the code leans on that - the forwarded
    // set's atomic add() is the bridge's guard. Jackson binding into the real object would hand
    // those fields a plain ArrayList/HashSet and quietly take the thread safety away.
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

    static final class TwinProcessDto {
        public String id;
        public String modelId;
        public String processDefinitionId;
        public String originalProcessId;
        public String twinProcessId;
        public String status;
        public Long launchedAtEpochMillis;
        public List<String> eventLog;
        public List<String> forwardedBridgeActivities;
        public List<ActivityLinkDto> activityLinks;

        static TwinProcessDto of(TwinProcess twin) {
            TwinProcessDto dto = new TwinProcessDto();
            dto.id = twin.getId();
            dto.modelId = twin.getModelId();
            dto.processDefinitionId = twin.getProcessDefinitionId();
            dto.originalProcessId = twin.getOriginalProcessId();
            dto.twinProcessId = twin.getTwinProcessId();
            dto.status = twin.getStatus();
            dto.launchedAtEpochMillis = twin.getLaunchedAt() == null
                    ? null
                    : twin.getLaunchedAt().toEpochMilli();
            dto.eventLog = new ArrayList<>(twin.getEventLog());
            dto.forwardedBridgeActivities = new ArrayList<>(twin.getForwardedBridgeActivities());
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
            twin.setOriginalProcessId(originalProcessId);
            twin.setTwinProcessId(twinProcessId);
            twin.setStatus(status);
            twin.setLaunchedAt(launchedAtEpochMillis == null
                    ? null
                    : Instant.ofEpochMilli(launchedAtEpochMillis));
            // add into the collections the constructor already made, don't replace them
            twin.getEventLog().addAll(nullToEmpty(eventLog));
            twin.getForwardedBridgeActivities().addAll(nullToEmpty(forwardedBridgeActivities));
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
