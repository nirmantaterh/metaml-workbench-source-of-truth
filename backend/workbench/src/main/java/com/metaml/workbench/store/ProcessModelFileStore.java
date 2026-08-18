package com.metaml.workbench.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Writes each saved model's raw BPMN as its own .bpmn file on the server filesystem, one file per
// model id. This is separate from WorkbenchStateStore, which already embeds the same XML as a
// string field inside its own shared workbench-state.json - that file is a restart-recovery cache
// for the workbench's own bookkeeping, not something meant to be opened or copied as a standalone
// file. The Spring Boot generation step needs an actual .bpmn file it can copy into a generated
// project's resources, and that's what this class exists to provide.
//
// Unlike WorkbenchStateStore, a write failure here is NOT swallowed - the generation step depends
// on this file actually existing, so a caller needs to know if it didn't get written.
@Component
public class ProcessModelFileStore {

    private static final Logger logger = LoggerFactory.getLogger(ProcessModelFileStore.class);

    private final Path directory;

    public ProcessModelFileStore(
            @Value("${workbench.models.directory:./data/models}") String directory) {
        this.directory = Path.of(directory);
    }

    public Path save(String modelId, String bpmnXml) {
        return save(pathFor(modelId), modelId, bpmnXml);
    }

    // Same file-per-model convention as save(), for a model's independently authored second BPMN
    // (see ProcessModel.authoredTwinBpmnXml). Kept as a distinct method/file rather than folding
    // into save() - callers that only ever deal in single-BPMN models (the common case) should
    // never need to pass a null twin XML through this class's main entry point.
    public Path saveTwin(String modelId, String twinBpmnXml) {
        return save(pathForTwin(modelId), modelId, twinBpmnXml);
    }

    private Path save(Path target, String modelId, String bpmnXml) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        try {
            Files.createDirectories(directory.toAbsolutePath());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, bpmnXml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Saved BPMN file for model {} to {}", modelId, target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not write BPMN file for model " + modelId + " to " + target.toAbsolutePath(), e);
        }
    }

    // WorkbenchServiceImpl already rejects a client-supplied id that isn't [A-Za-z0-9_-]+, but this
    // class is a plain @Component anything can call, and its whole job is turning a string into a
    // filesystem path - it shouldn't be the caller's business to have got that right first. A
    // modelId of "../../evil" or "C:/Windows/evil" resolves cleanly to somewhere outside the
    // configured models directory, and nothing in save() below would have noticed. Normalising and
    // re-checking containment is the cheap way to make that structurally impossible rather than
    // conventionally unlikely.
    public Path pathFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(modelId + ".bpmn").normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "modelId must not resolve outside the models directory: " + modelId);
        }
        return resolved;
    }

    // Same containment guarantee as pathFor(), for the authored-twin file.
    public Path pathForTwin(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(modelId + ".twin.bpmn").normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "modelId must not resolve outside the models directory: " + modelId);
        }
        return resolved;
    }

    public boolean exists(String modelId) {
        return Files.isRegularFile(pathFor(modelId));
    }

    public boolean existsTwin(String modelId) {
        return Files.isRegularFile(pathForTwin(modelId));
    }

    // Deleting a model's BPMN artifact(s). Unlike save() above, a failure here is logged rather than
    // thrown, and that asymmetry is deliberate: save()'s caller genuinely cannot continue without
    // the file (the generation step reads it), whereas delete()'s caller is removing the model
    // outright - a .bpmn file left behind is inert, referenced by nothing, and not worth failing a
    // deletion that has otherwise fully succeeded. pathFor() supplies the same containment check
    // every other method here relies on, so a hostile id cannot reach outside the models directory.
    // The twin file's own deleteIfExists is a no-op for a model that never had one, so this stays
    // safe to call unconditionally regardless of which kind of model modelId names.
    public boolean delete(String modelId) {
        boolean deleted = deleteIfExists(pathFor(modelId), modelId);
        deleteIfExists(pathForTwin(modelId), modelId);
        return deleted;
    }

    private boolean deleteIfExists(Path target, String modelId) {
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                logger.info("Deleted BPMN file for model {} at {}", modelId, target.toAbsolutePath());
            }
            return deleted;
        } catch (IOException e) {
            logger.warn("Could not delete BPMN file for model {} at {}: {}", modelId, target.toAbsolutePath(),
                    e.toString());
            return false;
        }
    }
}
