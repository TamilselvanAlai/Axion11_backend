package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "batches")
@SQLRestriction("deleted_at IS NULL")
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    private String status = "PENDING";
    private Integer completion;
    private String assignedTo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "team_id")
    private Team team;

    private String dueDate;
    private String priority;

    @Column(length = 2000)
    private String notes;

    /** PENDING, UPLOADING, COMPLETED, FAILED */
    @Builder.Default
    private String uploadStatus = "PENDING";

    private Integer totalImages;
    private Integer uploadedImages;

    // For Culling batches
    private Integer totalAssets;
    private Integer selectedAssets;
    private Integer rejectedAssets;
    private String selectionStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_batch_id")
    private Batch parentBatch;

    @Builder.Default
    @OneToMany(mappedBy = "parentBatch", fetch = FetchType.LAZY)
    private List<Batch> childBatches = new ArrayList<>();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Asset> assets;

    @Builder.Default
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ImageUpload> imageUploads = new ArrayList<>();

    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
