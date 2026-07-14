package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectTreeNode {
    private String id;
    private String name;
    private String type; // "project" | "batch" | "asset"
    private String status;
    private List<ProjectTreeNode> children;

    // Parent project id for batch/asset nodes (e.g. "p-42") — used by the DAM search bar
    private String projectId;

    // Batch/project-level fields
    private String uploadStatus;      // PENDING, UPLOADING, COMPLETED, FAILED
    private Integer totalAssets;
    private Integer approvedCount;
    private Integer pendingCount;
    private Integer rejectedCount;
    private String assignedTo;
    private Long teamId;
    private String dueDate;
    private String priority;
    private String createdAt;

    // Asset-specific fields (only populated for type="asset")
    private String thumbnail;
    private Integer aiScore;
    private String dimensions;
    private String fileSize;
    private String uploadedBy;
    private String uploadDate;
    private Integer commentsCount;
    private String angle;           // tagged photo angle (Front, Back, Left, Right, Top, Detail)
    private String workflowStatus;  // PENDING, WORK_IN_PROGRESS, MOVE_TO_QC, REVIEWED_READY_FOR_APPROVAL, READY_FOR_PRODUCTION
    private Integer versionNumber;
    private Long originalUploadId;
    private String assignedToName;
}
