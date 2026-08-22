package com.metaml.workbench.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.metaml.workbench.constants.ProjectStatus;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // Assigned after H2 has generated id, e.g. PROJECT-000001.  This is the stable,
    // human-friendly identifier shown in the Project UI.
    // Nullable only for rows created by earlier releases before this column existed; every new
    // project is assigned a value in ProjectServiceImpl immediately after its id is generated.
    @Column(unique = true, length = 50)
    private String displayName;

    @Column(length = 500)
    private String description;

    @Column(length = 150)
    @Enumerated(EnumType.STRING)
    private ProjectStatus status;

    @CreationTimestamp
    private LocalDateTime createdOn;

    @UpdateTimestamp
    private LocalDateTime lastUpdatedOn;

    @OneToMany(mappedBy = "project")
    private List<ProcessModelArchive> processModels = new ArrayList<>();
}
