package com.axion11.visualops.service;

import com.axion11.visualops.models.Batch;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.models.Project;
import com.axion11.visualops.models.dto.ProjectTreeNode;
import com.axion11.visualops.repository.BatchRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProjectTreeService {

    private final ProjectRepository projectRepository;
    private final BatchRepository batchRepository;
    private final ImageUploadRepository imageUploadRepository;
    private final ProjectAccessService projectAccessService;

    public List<ProjectTreeNode> getProjectTree() {
        java.util.Set<Long> allowed = projectAccessService.allowedProjectIds();
        java.util.stream.Stream<Project> stream = projectRepository.findAll().stream();
        if (allowed != null) {
            stream = stream.filter(p -> allowed.contains(p.getId()));
        }
        return stream.map(this::mapProject).collect(Collectors.toList());
    }

    private ProjectTreeNode mapProject(Project project) {
        List<ProjectTreeNode> batchNodes = batchRepository
                .findByProjectIdAndParentBatchIsNull(project.getId()).stream()
                .map(b -> mapBatch(b, project))
                .collect(Collectors.toList());

        int totalApproved = batchNodes.stream().mapToInt(b -> b.getApprovedCount() != null ? b.getApprovedCount() : 0).sum();
        int totalPending = batchNodes.stream().mapToInt(b -> b.getPendingCount() != null ? b.getPendingCount() : 0).sum();
        int totalRejected = batchNodes.stream().mapToInt(b -> b.getRejectedCount() != null ? b.getRejectedCount() : 0).sum();
        int totalAssets = batchNodes.stream().mapToInt(b -> b.getTotalAssets() != null ? b.getTotalAssets() : 0).sum();

        return ProjectTreeNode.builder()
                .id("p-" + project.getId())
                .name(project.getName())
                .type("project")
                .children(batchNodes)
                .totalAssets(totalAssets)
                .approvedCount(totalApproved)
                .pendingCount(totalPending)
                .rejectedCount(totalRejected)
                .createdAt(project.getCreatedAt() != null ? project.getCreatedAt().toString() : null)
                .build();
    }

    private ProjectTreeNode mapBatch(Batch batch, Project project) {
        List<ImageUpload> uploads = imageUploadRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId());

        List<ProjectTreeNode> childNodes = new ArrayList<>();  // asset nodes built below
        for (ImageUpload upload : uploads) {
            String sizeStr = upload.getFileSize() != null
                    ? formatFileSize(upload.getFileSize()) : "";

            String angle = upload.getTags().stream()
                    .filter(t -> "angle".equalsIgnoreCase(t.getCategory()))
                    .map(t -> t.getValue())
                    .findFirst()
                    .orElse(null);

            childNodes.add(ProjectTreeNode.builder()
                    .id(String.valueOf(upload.getId()))
                    .name(upload.getFileName())
                    .type("asset")
                    .status(upload.getApprovalStatus())
                    .thumbnail(upload.getPreviewUrl() != null ? upload.getPreviewUrl() : upload.getPublicUrl())
                    .aiScore(upload.getAiScore())
                    .fileSize(sizeStr)
                    .dimensions(upload.getWidth() != null && upload.getHeight() != null
                            ? upload.getWidth() + " × " + upload.getHeight()
                            : null)
                    .uploadedBy(upload.getUploadedBy() != null ? upload.getUploadedBy().getName() : null)
                    .uploadDate(upload.getCreatedAt() != null ? upload.getCreatedAt().toString() : null)
                    .commentsCount(upload.getComments() != null ? upload.getComments().size() : 0)
                    .angle(angle)
                    .workflowStatus(upload.getWorkflowStatus())
                    .versionNumber(upload.getVersionNumber() != null ? upload.getVersionNumber() : 1)
                    .originalUploadId(upload.getOriginalUploadId())
                    .assignedToName(upload.getAssignedToName())
                    .build());
        }

        // Prepend sub-batch nodes before asset nodes
        List<ProjectTreeNode> subBatchNodes = batchRepository.findByParentBatchId(batch.getId()).stream()
                .map(b -> mapBatch(b, project))
                .collect(Collectors.toList());
        List<ProjectTreeNode> allChildren = new ArrayList<>(subBatchNodes);
        allChildren.addAll(childNodes);
        childNodes = allChildren;

        int approved = imageUploadRepository.countApprovedByBatchId(batch.getId());
        int rejected = imageUploadRepository.countRejectedByBatchId(batch.getId());
        int pending = imageUploadRepository.countPendingByBatchId(batch.getId());
        int total = (int) uploads.size();

        // Roll up counts from sub-batches so a parent batch's totals reflect assets nested
        // inside its descendants too, not just files uploaded directly to it.
        for (ProjectTreeNode sub : subBatchNodes) {
            total += sub.getTotalAssets() != null ? sub.getTotalAssets() : 0;
            approved += sub.getApprovedCount() != null ? sub.getApprovedCount() : 0;
            rejected += sub.getRejectedCount() != null ? sub.getRejectedCount() : 0;
            pending += sub.getPendingCount() != null ? sub.getPendingCount() : 0;
        }

        String workflowStatus = batch.getStatus();
        String uploadStatus = batch.getUploadStatus();
        if ("UPLOADING".equals(uploadStatus) || "PENDING".equals(uploadStatus)) {
            workflowStatus = "Upload in Progress";
        }

        return ProjectTreeNode.builder()
                .id("b-" + batch.getId())
                .projectId("p-" + project.getId())
                .name(batch.getName())
                .type("batch")
                .status(workflowStatus)
                .uploadStatus(uploadStatus)
                .totalAssets(total)
                .approvedCount(approved)
                .pendingCount(pending)
                .rejectedCount(rejected)
                .assignedTo(batch.getAssignedTo())
                .teamId(batch.getTeam() != null ? batch.getTeam().getId() : null)
                .dueDate(batch.getDueDate())
                .priority(batch.getPriority())
                .createdAt(batch.getCreatedAt() != null ? batch.getCreatedAt().toString() : null)
                .children(childNodes)
                .build();
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
