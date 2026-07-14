package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "image_uploads")
@SQLRestriction("deleted_at IS NULL")
public class ImageUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false, length = 1000)
    private String gcsPath;

    @Column(nullable = false, length = 1000)
    private String publicUrl;

    private String contentType;
    private Long fileSize;

    @Column(unique = true)
    private String externalId;

    /** 1-based sequential version number. Incremented each time a file with the same name is uploaded to the same batch. */
    @Builder.Default
    @Column(nullable = false)
    private Integer versionNumber = 1;

    /** Points to the ID of the very first version of this file. Null for v1 records. */
    @Column(name = "original_upload_id")
    private Long originalUploadId;

    /** Virtual folder path for files synced from external drives (Google Drive, OneDrive). Null for normal uploads. */
    @Column(name = "source_path", length = 2048)
    private String sourcePath;

    private Integer aiScore;

    /** approved, pending, rejected — asset-level approval status */
    @Builder.Default
    @Column(length = 20)
    private String approvalStatus = "pending";

    /** PENDING, UPLOADING, COMPLETED, FAILED */
    @Builder.Default
    private String uploadStatus = "PENDING";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private Batch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private User uploadedBy;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** PENDING, PASSED, WARNING, REJECTED */
    @Column(length = 20)
    private String imageQualityQcCheck;

    @Column(length = 2000)
    private String qcDetails;

    private Integer width;
    private Integer height;

    @Column(length = 50)
    private String colorSpace;

    private Integer dpiX;
    private Integer dpiY;

    @Column(length = 500)
    private String imageTitle;

    @Column(length = 1000)
    private String altText;

    @Column(length = 2000)
    private String description;

    /** JPEG preview URL for non-browser-renderable formats (PSD, AI, EPS, etc.) */
    @Column(length = 500)
    private String previewUrl;

    @Builder.Default
    @OneToMany(mappedBy = "imageUpload", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    /** Workflow status: PENDING, MOVE_TO_QC, REVIEWED_READY_FOR_APPROVAL, READY_FOR_PRODUCTION */
    @Builder.Default
    @Column(length = 50)
    private String workflowStatus = "PENDING";

    @Builder.Default
    @OneToMany(mappedBy = "imageUpload", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ImageTag> tags = new ArrayList<>();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "assigned_to_user_id")
    private Long assignedToUserId;

    /** Denormalized display name, kept in sync with assignedToUserId — avoids a join for the asset grid. */
    @Column(name = "assigned_to_name", length = 255)
    private String assignedToName;
}
