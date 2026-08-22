package com.metaml.workbench.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

import com.metaml.workbench.model.ProcessModelArchive;

public interface ProcessModelArchiveRepository extends JpaRepository<ProcessModelArchive, Long> {
    Optional<ProcessModelArchive> findByModelId(String modelId);

    List<ProcessModelArchive> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);

    void deleteByModelId(String modelId);
}
