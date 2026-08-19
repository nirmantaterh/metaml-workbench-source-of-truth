package com.metaml.workbench.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import com.metaml.workbench.model.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByName(String projectName);
}
