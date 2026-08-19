package com.metaml.workbench.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "process_model_archives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessModelArchive {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Lob
    private String bpmnXml;

    @Column(length = 255)
    private String bpmnFilePath;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private String processDefinitionId;

    private String tenantId;

    private Integer major;
    private Integer minor;
    private Integer patch;
    private String preRelease;
    private String buildMetadata;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;
}
