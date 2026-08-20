package com.metaml.workbench.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import com.metaml.workbench.model.ProcessModelArchive;

public interface ProcessModelArchiveRepository extends JpaRepository<ProcessModelArchive, Long> {
    Optional<ProcessModelArchive> findByModelId(String modelId);

    void deleteByModelId(String modelId);
}
