package com.axion11.visualops.service;

import com.axion11.visualops.controller.dto.ImageTagDto;
import com.axion11.visualops.controller.dto.ImageUploadDto;
import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageUploadService {

    private final ImageUploadRepository imageUploadRepository;
    private final ImageTagRepository imageTagRepository;
    private final FaceGroupRepository faceGroupRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final BatchRepository batchRepository;
    private final CommentRepository commentRepository;
    private final CommentClassifierService commentClassifier;
    private final AuditService auditService;
    private final ImageQcService imageQcService;
    private final ProjectAccessService projectAccessService;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String geminiModel;

    /**
     * In-memory cache of face-group thumbnail bytes, keyed by GCS public URL. Populated lazily on
     * the first face-match call that needs each thumbnail and reused across subsequent calls so we
     * don't re-download the same thumbnail from GCS for every face we match. Entries are immutable
     * (UUID-named GCS objects); we evict on merge.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> faceThumbnailCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Per-project locks that serialize face-group create/match decisions across concurrent Phase 2
     * tasks. Without this, two threads detecting the same new person in different images would both
     * miss the match and create duplicate person_N rows.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> faceGroupLocks = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public List<ImageUploadDto> uploadImages(List<MultipartFile> files, Long projectId, String uploaderEmail) {
        User uploader = userRepository.findByEmail(uploaderEmail).orElse(null);
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;

        List<ImageUploadDto> results = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                results.add(processSingleUpload(file, project, uploader));
            } catch (Exception e) {
                log.error("Failed to process upload for file {}: {}", file.getOriginalFilename(), e.getMessage(), e);
            }
        }
        return results;
    }

    public List<ImageUploadDto> getUploads(Long projectId) {
        Set<Long> allowed = projectAccessService.allowedProjectIds();
        // Explicit project filter — gate on access first
        if (projectId != null) {
            if (allowed != null && !allowed.contains(projectId)) {
                throw new NoSuchElementException("Project not found: " + projectId);
            }
            return imageUploadRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                    .map(this::toDto).collect(Collectors.toList());
        }
        // No project filter — for non-bypass users, restrict to uploads in their team's projects.
        if (allowed != null) {
            if (allowed.isEmpty()) return List.of();
            return imageUploadRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(u -> u.getProject() != null && allowed.contains(u.getProject().getId()))
                    .map(this::toDto).collect(Collectors.toList());
        }
        return imageUploadRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    public ImageUploadDto getUpload(Long id) {
        return getUpload(id, null);
    }

    /** @param actingUsername when set, logs an ASSET_VIEW event — used only by the explicit
     *  single-asset detail fetch (powers "Recent"), not internal/bulk lookups. */
    public ImageUploadDto getUpload(Long id, String actingUsername) {
        ImageUpload upload = imageUploadRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + id));
        if (upload.getProject() != null && !projectAccessService.canAccess(upload.getProject())) {
            throw new NoSuchElementException("Upload not found: " + id);
        }
        if (actingUsername != null) {
            userRepository.findByEmail(actingUsername).ifPresent(u ->
                    auditWithUser(upload, "ASSET_VIEW", "Viewed " + upload.getFileName(), u.getId()));
        }
        return toDto(upload);
    }

    /** Logs an ASSET_DOWNLOAD event without transferring the file — see controller javadoc. */
    public void recordDownload(Long uploadId, String actingUsername) {
        imageUploadRepository.findById(uploadId).ifPresent(upload ->
                userRepository.findByEmail(actingUsername).ifPresent(u ->
                        auditWithUser(upload, "ASSET_DOWNLOAD", "Downloaded " + upload.getFileName(), u.getId())));
    }

    /** Fetches uploads by id, preserving the given order (e.g. most-recent-access-first from
     *  an audit query) and silently skipping ids that are missing or no longer accessible. */
    public List<ImageUploadDto> getUploadsByIdsInOrder(List<Long> ids) {
        List<ImageUploadDto> result = new java.util.ArrayList<>();
        for (Long id : ids) {
            imageUploadRepository.findById(id).ifPresent(upload -> {
                if (upload.getProject() == null || projectAccessService.canAccess(upload.getProject())) {
                    result.add(toDto(upload));
                }
            });
        }
        return result;
    }

    @org.springframework.transaction.annotation.Transactional
    public void softDeleteUpload(Long id) {
        imageUploadRepository.softDeleteById(id, java.time.LocalDateTime.now());
    }

    public List<ImageUploadDto> batchRename(List<java.util.Map<String, Object>> renames) {
        List<ImageUploadDto> results = new ArrayList<>();
        for (java.util.Map<String, Object> entry : renames) {
            Long id = Long.valueOf(entry.get("id").toString());
            String newFileName = (String) entry.get("newFileName");
            imageUploadRepository.findById(id).ifPresent(upload -> {
                upload.setFileName(newFileName);
                imageUploadRepository.save(upload);
                results.add(toDto(upload));
            });
        }
        return results;
    }

    /**
     * Search images by tag criteria.
     *
     * @param tags      list of "category:value" strings (e.g. ["color:Blue", "gender:female"]).
     *                  AND semantics — an image must match every criterion to be included.
     *                  Value matching is case-insensitive and partial (contains).
     * @param projectId optional project scope
     */
    public List<ImageUploadDto> searchByTags(List<String> tags, Long projectId) {
        if (tags == null || tags.isEmpty()) {
            return getUploads(projectId);
        }

        Set<Long> allowed = projectAccessService.allowedProjectIds();
        if (projectId != null && allowed != null && !allowed.contains(projectId)) {
            // Asked for a specific project the user can't see — return nothing rather than 403.
            return List.of();
        }

        Set<Long> matchingIds = null;
        for (String tagSpec : tags) {
            int colon = tagSpec.indexOf(':');
            if (colon < 0) continue; // skip malformed entries
            String category = tagSpec.substring(0, colon).trim();
            String value    = tagSpec.substring(colon + 1).trim();
            if (category.isEmpty()) continue;

            Set<Long> ids = imageTagRepository.findUploadIdsByCategoryAndValue(category, value, projectId);
            if (matchingIds == null) {
                matchingIds = new HashSet<>(ids);
            } else {
                matchingIds.retainAll(ids); // AND — keep only images matching all criteria
            }
            if (matchingIds.isEmpty()) break; // short-circuit
        }

        if (matchingIds == null || matchingIds.isEmpty()) {
            return List.of();
        }

        java.util.stream.Stream<ImageUpload> stream = imageUploadRepository.findAllById(matchingIds).stream();
        if (allowed != null) {
            // Cross-project search for non-bypass user — drop uploads from projects they can't see.
            stream = stream.filter(u -> u.getProject() == null || allowed.contains(u.getProject().getId()));
        }
        return stream
                .sorted(Comparator.comparing(ImageUpload::getCreatedAt).reversed())
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Case-insensitive file-name/keyword search — powers the Assets/DAM toolbar search bar.
     * Unlike {@link #searchByTags}, this matches on the asset's actual file name rather than
     * AI-assigned tags. When {@code projectId} is null, searches across every project the
     * caller has access to instead of scoping to one.
     */
    public List<ImageUploadDto> searchByFileName(Long projectId, String query) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        Set<Long> allowed = projectAccessService.allowedProjectIds();
        if (projectId != null) {
            if (allowed != null && !allowed.contains(projectId)) {
                return List.of();
            }
            return imageUploadRepository
                    .findByProjectIdAndFileNameContainingIgnoreCaseOrderByCreatedAtDesc(projectId, query.trim())
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        }
        Stream<ImageUpload> stream = imageUploadRepository
                .findByFileNameContainingIgnoreCaseOrderByCreatedAtDesc(query.trim())
                .stream();
        if (allowed != null) {
            stream = stream.filter(u -> u.getProject() != null && allowed.contains(u.getProject().getId()));
        }
        return stream.map(this::toDto).collect(Collectors.toList());
    }

    // ── Face group queries ──────────────────────────────────────────────────

    /**
     * Returns all distinct face_id groups with occurrence counts and a representative thumbnail.
     */
    public List<com.axion11.visualops.controller.dto.FaceGroupDto> getFaceGroups() {
        Set<Long> allowed = projectAccessService.allowedProjectIds();
        List<Object[]> rows = imageTagRepository.findFaceGroupsWithCounts();
        log.info("getFaceGroups: query returned {} rows", rows.size());
        List<com.axion11.visualops.controller.dto.FaceGroupDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            String faceLabel = (String) row[0];
            List<Long> uploadIds = imageTagRepository.findUploadIdsByFaceLabel(faceLabel);

            // For non-bypass users, restrict the upload-id list to projects the user can see.
            // If that leaves the group empty, skip it entirely (don't expose its existence).
            if (allowed != null) {
                List<ImageUpload> uploads = imageUploadRepository.findAllById(uploadIds);
                uploadIds = uploads.stream()
                        .filter(u -> u.getProject() != null && allowed.contains(u.getProject().getId()))
                        .map(ImageUpload::getId)
                        .collect(Collectors.toList());
                if (uploadIds.isEmpty()) continue;
            }
            long count = uploadIds.size();

            // Use cropped face thumbnail from FaceGroup; fall back to full image
            List<FaceGroup> faceGroups = faceGroupRepository.findByGroupLabel(faceLabel);
            String thumbnailUrl = faceGroups.stream()
                    .map(FaceGroup::getFaceThumbnailUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .findFirst()
                    .orElse(null);
            if (thumbnailUrl == null && !uploadIds.isEmpty()) {
                ImageUpload first = imageUploadRepository.findById(uploadIds.get(0)).orElse(null);
                if (first != null) thumbnailUrl = first.getPublicUrl();
            }

            log.info("getFaceGroups: label={}, count={}, thumbnail={}, uploadIds={}", faceLabel, count, thumbnailUrl, uploadIds);
            result.add(new com.axion11.visualops.controller.dto.FaceGroupDto(faceLabel, count, thumbnailUrl, uploadIds));
        }
        log.info("getFaceGroups: returning {} face groups", result.size());
        return result;
    }

    /**
     * Merges all images tagged face_id=sourceLabel into face_id=targetLabel and deletes
     * the orphaned FaceGroup row. Used when Gemini split the same person across two groups.
     */
    @org.springframework.transaction.annotation.Transactional
    public void mergeFaceGroups(String sourceLabel, String targetLabel) {
        if (sourceLabel == null || targetLabel == null
                || sourceLabel.isBlank() || targetLabel.isBlank()
                || sourceLabel.equals(targetLabel)) {
            throw new IllegalArgumentException("sourceLabel and targetLabel must be distinct non-empty values");
        }
        // Evict cached thumbnail bytes for the source group before deleting the row.
        faceGroupRepository.findByGroupLabel(sourceLabel).forEach(g -> {
            if (g.getFaceThumbnailUrl() != null) faceThumbnailCache.remove(g.getFaceThumbnailUrl());
        });
        // Find images that already carry the target label and drop the source label from those —
        // otherwise the relabel below would create a duplicate face_id=target tag on the same image.
        Set<Long> targetImageIds = new HashSet<>(imageTagRepository.findUploadIdsByFaceLabel(targetLabel));
        Set<Long> sourceImageIds = new HashSet<>(imageTagRepository.findUploadIdsByFaceLabel(sourceLabel));
        sourceImageIds.retainAll(targetImageIds);
        if (!sourceImageIds.isEmpty()) {
            imageTagRepository.deleteFaceTagsByLabelAndImageIds(sourceLabel, sourceImageIds);
        }
        int relabeled = imageTagRepository.relabelFaceTags(sourceLabel, targetLabel);
        faceGroupRepository.deleteByGroupLabel(sourceLabel);
        log.info("Merged face group {} into {}: {} tags relabeled", sourceLabel, targetLabel, relabeled);
    }

    /**
     * Returns all images that contain the given face_id label.
     */
    public List<ImageUploadDto> getImagesByFaceId(String faceLabel) {
        List<Long> uploadIds = imageTagRepository.findUploadIdsByFaceLabel(faceLabel);
        if (uploadIds.isEmpty()) return List.of();
        Set<Long> allowed = projectAccessService.allowedProjectIds();
        return imageUploadRepository.findAllById(uploadIds).stream()
                .filter(u -> allowed == null
                        || (u.getProject() != null && allowed.contains(u.getProject().getId())))
                .sorted(Comparator.comparing(ImageUpload::getCreatedAt).reversed())
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Adds a new tag to an upload (allows multiple values per category, e.g. multiple colors).
     */
    public void addTag(Long uploadId, String category, String value) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));
        ImageTag newTag = tag(upload, category, value, 1.0);
        imageTagRepository.save(newTag);
        upload.getTags().add(newTag);
        audit(upload, "TAG_EDIT", "Added " + category + ":" + value);
    }

    /**
     * Upserts a singleton tag — replaces the existing value for the category, or creates it.
     */
    public void upsertTag(Long uploadId, String category, String value) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        Optional<ImageTag> existing = upload.getTags().stream()
                .filter(t -> category.equalsIgnoreCase(t.getCategory()))
                .findFirst();

        String oldValue = existing.map(ImageTag::getValue).orElse(null);
        if (existing.isPresent()) {
            existing.get().setValue(value);
            imageTagRepository.save(existing.get());
        } else {
            ImageTag newTag = tag(upload, category, value, 1.0);
            imageTagRepository.save(newTag);
            upload.getTags().add(newTag);
        }
        String detail = oldValue != null
                ? "Changed " + category + " from " + oldValue + " to " + value
                : "Added " + category + ":" + value;
        audit(upload, "TAG_EDIT", detail);
    }

    /**
     * Deletes a specific tag by category and value.
     */
    public void deleteTag(Long uploadId, String category, String value) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        upload.getTags().stream()
                .filter(t -> category.equalsIgnoreCase(t.getCategory()) && value.equalsIgnoreCase(t.getValue()))
                .findFirst()
                .ifPresent(t -> {
                    upload.getTags().remove(t);
                    imageTagRepository.delete(t);
                });
        audit(upload, "TAG_DELETE", "Removed " + category + ":" + value);
    }

    /**
     * Updates or creates the angle tag for a given upload.
     */
    public void updateAngleTag(Long uploadId, String angle) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        Optional<ImageTag> existing = upload.getTags().stream()
                .filter(t -> "angle".equalsIgnoreCase(t.getCategory()))
                .findFirst();

        String oldValue = existing.map(ImageTag::getValue).orElse(null);
        if (existing.isPresent()) {
            existing.get().setValue(angle);
            imageTagRepository.save(existing.get());
        } else {
            ImageTag newTag = tag(upload, "angle", angle, 1.0);
            imageTagRepository.save(newTag);
            upload.getTags().add(newTag);
        }
        String detail = oldValue != null
                ? "Changed angle from " + oldValue + " to " + angle
                : "Added angle:" + angle;
        audit(upload, "TAG_EDIT", detail);
    }

    /**
     * Updates the SEO fields (imageTitle, altText, description) for an upload.
     */
    public List<com.axion11.visualops.controller.dto.CommentDto> getComments(Long uploadId) {
        List<Comment> comments = commentRepository.findByImageUploadId(uploadId);
        return comments.stream().map(c -> {
            var dto = new com.axion11.visualops.controller.dto.CommentDto();
            dto.setId(c.getId());
            dto.setText(c.getText());
            dto.setAuthorName(c.getAuthorName());
            dto.setCreatedAt(c.getCreatedAt());
            dto.setResolved(c.isResolved());
            dto.setAnnotationImageUrl(c.getAnnotationImageUrl());
            return dto;
        }).collect(Collectors.toList());
    }

    public com.axion11.visualops.controller.dto.CommentDto addComment(Long uploadId, String text, String authorName) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        var classification = commentClassifier.classify(text);
        Comment comment = Comment.builder()
                .text(text)
                .authorName(authorName)
                .imageUpload(upload)
                .feedbackCategory(classification != null ? classification.category() : null)
                .feedbackSubcategory(classification != null ? classification.subcategory() : null)
                .feedbackSeverity(classification != null ? classification.severity() : null)
                .build();
        comment = commentRepository.save(comment);

        var dto = new com.axion11.visualops.controller.dto.CommentDto();
        dto.setId(comment.getId());
        dto.setText(comment.getText());
        dto.setAuthorName(comment.getAuthorName());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setResolved(comment.isResolved());
        dto.setAnnotationImageUrl(comment.getAnnotationImageUrl());
        return dto;
    }

    public org.springframework.http.ResponseEntity<byte[]> downloadFile(Long uploadId) {
        return downloadFile(uploadId, null);
    }

    /** @param actingUsername when set, logs an ASSET_DOWNLOAD event on success — powers "Transfers". */
    public org.springframework.http.ResponseEntity<byte[]> downloadFile(Long uploadId, String actingUsername) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));
        try {
            Storage storage = StorageOptions.getDefaultInstance().getService();
            // Strip gs://bucket-name/ prefix if present to get the object name
            String objectName = upload.getGcsPath();
            String gsPrefix = "gs://" + bucketName + "/";
            if (objectName.startsWith(gsPrefix)) {
                objectName = objectName.substring(gsPrefix.length());
            } else if (objectName.startsWith("gs://")) {
                objectName = objectName.substring(objectName.indexOf('/', 5) + 1);
            }
            byte[] content = storage.readAllBytes(BlobId.of(bucketName, objectName));
            if (actingUsername != null) {
                userRepository.findByEmail(actingUsername).ifPresent(u ->
                        auditWithUser(upload, "ASSET_DOWNLOAD", "Downloaded " + upload.getFileName(), u.getId()));
            }
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + upload.getFileName() + "\"")
                    .header("Content-Type", upload.getContentType() != null ? upload.getContentType() : "application/octet-stream")
                    .body(content);
        } catch (Exception e) {
            log.error("Failed to download file {}: {}", uploadId, e.getMessage());
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    public java.util.Map<String, String> openFileForEditing(Long uploadId) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        // Check if macOS `open` command is available (local dev)
        boolean isMacOS = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (isMacOS) {
            try {
                Storage storage = StorageOptions.getDefaultInstance().getService();
                String objectName = upload.getGcsPath();
                String gsPrefix = "gs://" + bucketName + "/";
                if (objectName.startsWith(gsPrefix)) {
                    objectName = objectName.substring(gsPrefix.length());
                } else if (objectName.startsWith("gs://")) {
                    objectName = objectName.substring(objectName.indexOf('/', 5) + 1);
                }
                byte[] content = storage.readAllBytes(BlobId.of(bucketName, objectName));

                java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("visualops-edit");
                java.nio.file.Path tempFile = tempDir.resolve(upload.getFileName());
                java.nio.file.Files.write(tempFile, content);

                String filePath = tempFile.toAbsolutePath().toString();
                Process ps = new ProcessBuilder("open", "-a", "Adobe Photoshop", filePath)
                        .redirectErrorStream(true).start();
                if (ps.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && ps.exitValue() == 0) {
                    return java.util.Map.of("message", "Opened in Adobe Photoshop", "action", "opened");
                }
                new ProcessBuilder("open", "-a", "Preview", filePath).start();
                return java.util.Map.of("message", "Opened in Preview", "action", "opened");
            } catch (Exception e) {
                log.error("Failed to open file {} locally: {}", uploadId, e.getMessage());
            }
        }

        // Cloud / non-macOS: return download URL for the frontend to handle
        return java.util.Map.of(
                "message", "Download to open",
                "action", "download",
                "downloadUrl", upload.getPublicUrl(),
                "fileName", upload.getFileName()
        );
    }

    /** Bulk-assigns a set of uploads to a member, or clears the assignment when userId is null. */
    @Transactional
    public List<ImageUploadDto> assignUploadsToUser(List<Long> uploadIds, Long userId) {
        User assignee = userId != null
                ? userRepository.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found: " + userId))
                : null;
        List<ImageUpload> uploads = imageUploadRepository.findAllById(uploadIds);
        for (ImageUpload upload : uploads) {
            upload.setAssignedToUserId(assignee != null ? assignee.getId() : null);
            upload.setAssignedToName(assignee != null ? assignee.getName() : null);
        }
        imageUploadRepository.saveAll(uploads);
        return uploads.stream().map(this::toDto).collect(Collectors.toList());
    }

    /** Moves a set of uploads into a different batch — the upload's project follows the new batch's. */
    @Transactional
    public List<ImageUploadDto> moveUploadsToBatch(List<Long> uploadIds, Long batchId) {
        Batch targetBatch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + batchId));
        List<ImageUpload> uploads = imageUploadRepository.findAllById(uploadIds);
        for (ImageUpload upload : uploads) {
            upload.setBatch(targetBatch);
            upload.setProject(targetBatch.getProject());
        }
        imageUploadRepository.saveAll(uploads);
        return uploads.stream().map(this::toDto).collect(Collectors.toList());
    }

    public void updateWorkflowStatus(Long uploadId, String workflowStatus) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        // Resolve the currently authenticated user
        Long currentUserId = null;
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            User currentUser = userRepository.findByEmail(email).orElse(null);
            if (currentUser != null) currentUserId = currentUser.getId();
        } catch (Exception ignore) {}

        if ("ASSIGN_TO_SELF".equals(workflowStatus)) {
            upload.setWorkflowStatus("WORK_IN_PROGRESS");
            imageUploadRepository.save(upload);
            auditWithUser(upload, "ASSIGN_TO_MYSELF", "Assigned to self — work in progress", currentUserId);
            return;
        }
        if ("REVIEWED_READY_FOR_APPROVAL".equals(workflowStatus)) {
            upload.setWorkflowStatus(workflowStatus);
            imageUploadRepository.save(upload);
            auditWithUser(upload, "READY_FOR_APPROVAL", "Reviewed and ready for approval", currentUserId);
            return;
        }
        upload.setWorkflowStatus(workflowStatus);
        // If moving to production, also mark upload status as approved
        if ("READY_FOR_PRODUCTION".equals(workflowStatus)) {
            upload.setUploadStatus("approved");
            imageUploadRepository.save(upload);
            auditWithUser(upload, "READY_FOR_PRODUCTION", "Marked as ready for production", currentUserId);
            return;
        }
        imageUploadRepository.save(upload);
        auditWithUser(upload, "WORKFLOW_STATUS_CHANGE", "Changed workflow status to " + workflowStatus, currentUserId);
    }

    public void updateSeoFields(Long uploadId, Map<String, String> fields) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        if (fields.containsKey("imageTitle")) upload.setImageTitle(fields.get("imageTitle"));
        if (fields.containsKey("altText")) upload.setAltText(fields.get("altText"));
        if (fields.containsKey("description")) upload.setDescription(fields.get("description"));
        imageUploadRepository.save(upload);
        audit(upload, "SEO_EDIT", "Updated SEO fields");
    }

    /** Convenience audit logger that extracts IDs from an upload (safe for async/detached contexts). */
    private void audit(ImageUpload upload, String eventType, String details) {
        Long projectId = null;
        Long batchId = null;
        Long userId = null;
        try { projectId = upload.getProject() != null ? upload.getProject().getId() : null; } catch (Exception ignore) {}
        try { batchId = upload.getBatch() != null ? upload.getBatch().getId() : null; } catch (Exception ignore) {}
        try { userId = upload.getUploadedBy() != null ? upload.getUploadedBy().getId() : null; } catch (Exception ignore) {}
        auditService.log(eventType, projectId, batchId, upload.getId(), userId, details);
    }

    /** Audit logger that uses an explicit userId (the acting user) instead of the uploader. */
    private void auditWithUser(ImageUpload upload, String eventType, String details, Long actingUserId) {
        Long projectId = null;
        Long batchId = null;
        try { projectId = upload.getProject() != null ? upload.getProject().getId() : null; } catch (Exception ignore) {}
        try { batchId = upload.getBatch() != null ? upload.getBatch().getId() : null; } catch (Exception ignore) {}
        auditService.log(eventType, projectId, batchId, upload.getId(), actingUserId, details);
    }

    /**
     * Re-runs Gemini analysis on all existing uploads and adds any missing tags
     * (e.g. angle tags that didn't exist when the images were first uploaded).
     */
    public int retagAll() {
        List<ImageUpload> uploads = imageUploadRepository.findAllByOrderByCreatedAtDesc();
        int updated = 0;
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

        for (ImageUpload upload : uploads) {
            try {
                // Collect existing tag keys so we don't duplicate
                Set<String> existingKeys = upload.getTags().stream()
                        .map(t -> t.getCategory().toLowerCase() + ":" + t.getValue().toLowerCase())
                        .collect(Collectors.toSet());

                // Check if angle tag already exists
                boolean hasAngle = upload.getTags().stream()
                        .anyMatch(t -> "angle".equalsIgnoreCase(t.getCategory()));
                if (hasAngle) continue;

                // Download image from GCS public URL
                byte[] imageBytes = downloadImage(httpClient, upload.getPublicUrl());
                if (imageBytes == null) {
                    log.warn("Could not download image for re-tag: {}", upload.getPublicUrl());
                    continue;
                }

                // Re-run Gemini analysis
                List<ImageTag> newTags = analyzeWithGemini(imageBytes, upload);

                // Only save tags that don't already exist
                List<ImageTag> tagsToSave = newTags.stream()
                        .filter(t -> !existingKeys.contains(t.getCategory().toLowerCase() + ":" + t.getValue().toLowerCase()))
                        .collect(Collectors.toList());

                if (!tagsToSave.isEmpty()) {
                    imageTagRepository.saveAll(tagsToSave);
                    upload.getTags().addAll(tagsToSave);
                    updated++;
                    log.info("Re-tagged {} with {} new tags: {}", upload.getFileName(), tagsToSave.size(),
                            tagsToSave.stream().map(t -> t.getCategory() + ":" + t.getValue()).collect(Collectors.joining(", ")));
                }
            } catch (Exception e) {
                log.error("Failed to re-tag upload {}: {}", upload.getId(), e.getMessage(), e);
            }
        }
        log.info("Re-tag complete: {}/{} uploads updated", updated, uploads.size());
        return updated;
    }

    // ── Core processing ───────────────────────────────────────────────────────

    private ImageUploadDto processSingleUpload(MultipartFile file, Project project, User uploader) throws Exception {
        return processUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getBytes(), project, uploader);
    }

    /**
     * Upload a single image from pre-read byte data (safe for async use).
     */
    public ImageUploadDto uploadSingleImage(String originalFilename, String contentType, long size, byte[] bytes, Long projectId, String uploaderEmail) {
        User uploader = userRepository.findByEmail(uploaderEmail).orElse(null);
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;
        return processUpload(originalFilename, contentType, size, bytes, project, uploader);
    }

    /**
     * Generates a signed URL for direct browser-to-GCS upload.
     * Uses service account credentials when available (Cloud Run), throws if not (local dev).
     */
    public Map<String, String> generateSignedUploadUrl(String originalFileName, String contentType) {
        try {
            Storage storage = StorageOptions.getDefaultInstance().getService();
            String gcsFileName = UUID.randomUUID() + "_" + originalFileName;
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsFileName))
                    .setContentType(contentType)
                    .build();

            java.net.URL signedUrl = storage.signUrl(blobInfo,
                    60, java.util.concurrent.TimeUnit.MINUTES,
                    Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.PUT),
                    Storage.SignUrlOption.withContentType());

            return Map.of(
                    "signedUrl", signedUrl.toString(),
                    "gcsFileName", gcsFileName,
                    "publicUrl", "https://storage.googleapis.com/" + bucketName + "/" + gcsFileName
            );
        } catch (Exception e) {
            log.warn("Failed to generate GCS signed URL (likely local dev without service account key): {}", e.getMessage());
            return Map.of(
                    "supported", "false",
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
            );
        }
    }

    /**
     * Confirms a file uploaded directly to GCS. Creates DB record and triggers async AI processing.
     */
    public ImageUploadDto confirmDirectUpload(String gcsFileName, String originalFileName, String contentType,
                                               long fileSize, Long projectId, Long batchId, String uploaderEmail) {
        User uploader = userRepository.findByEmail(uploaderEmail).orElse(null);
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;

        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + gcsFileName;
        String gcsPath = "gs://" + bucketName + "/" + gcsFileName;

        ImageUpload imageUpload;
        synchronized (versionLockFor(originalFileName, batchId)) {
            long[] ver = resolveVersion(originalFileName, batchId);

            imageUpload = ImageUpload.builder()
                    .fileName(originalFileName)
                    .gcsPath(gcsPath)
                    .publicUrl(publicUrl)
                    .contentType(contentType)
                    .fileSize(fileSize)
                    .project(project)
                    .uploadedBy(uploader)
                    .createdAt(LocalDateTime.now())
                    .versionNumber((int) ver[0])
                    .originalUploadId(ver[1] < 0 ? null : ver[1])
                    .build();

            if (batchId != null) {
                Batch batch = batchRepository.findById(batchId).orElse(null);
                if (batch != null) {
                    imageUpload.setBatch(batch);
                    imageUpload.setUploadStatus("COMPLETED");
                }
            }

            imageUpload = imageUploadRepository.save(imageUpload);
        }

        // Increment batch upload counter
        if (batchId != null) {
            batchRepository.incrementUploadedImages(batchId);
            batchRepository.completeIfAllUploaded(batchId);
        }

        Long pid = project != null ? project.getId() : null;
        Long bid = batchId;
        Long uid = uploader != null ? uploader.getId() : null;
        auditService.log("IMAGE_UPLOAD", pid, bid, imageUpload.getId(), uid, "Uploaded " + originalFileName);

        // Async: download from GCS and run metadata extraction + AI tagging
        final Long uploadId = imageUpload.getId();
        final String ct = contentType != null ? contentType : "";
        final long maxDownloadSize = 40L * 1024 * 1024; // 40MB Vision API limit

        if (ct.startsWith("image/")) {
            // Process synchronously (Cloud Run kills async threads after response)
            try {
                ImageUpload u = imageUploadRepository.findById(uploadId).orElse(null);
                if (u != null) {
                    if (fileSize <= maxDownloadSize) {
                        byte[] bytes = downloadFromGcs(gcsFileName);
                        if (bytes != null) {
                            extractAndSaveMetadata(u, bytes);
                            analyzeImageDeferred(uploadId, bytes);
                        }
                    } else {
                        log.info("File {} is {}MB, too large for Vision API. Generating preview.", originalFileName, fileSize / (1024 * 1024));
                        try {
                            byte[] fullBytes = downloadFromGcs(gcsFileName);
                            if (fullBytes != null) {
                                extractAndSaveMetadata(u, fullBytes);
                                fullBytes = null;
                            }
                        } catch (Exception metaEx) {
                            log.error("Metadata extraction failed for large file {}: {}", originalFileName, metaEx.getMessage());
                        }
                        u = imageUploadRepository.findById(uploadId).orElse(null);
                        if (u != null && u.getPreviewUrl() != null) {
                            String previewGcsName = u.getPreviewUrl().replace("https://storage.googleapis.com/" + bucketName + "/", "");
                            byte[] previewBytes = downloadFromGcs(previewGcsName);
                            if (previewBytes != null) {
                                log.info("Using preview ({} bytes) for AI tagging of {}", previewBytes.length, originalFileName);
                                analyzeImageDeferred(uploadId, previewBytes);
                            }
                        } else {
                            log.warn("No preview generated for {}, skipping AI tagging", originalFileName);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Processing failed for confirmed upload {}: {}", uploadId, e.getMessage(), e);
            }
        }

        return toDto(imageUpload);
    }

    private byte[] downloadFromGcs(String gcsFileName) {
        try {
            Storage storage = StorageOptions.getDefaultInstance().getService();
            return storage.readAllBytes(BlobId.of(bucketName, gcsFileName));
        } catch (Exception e) {
            log.warn("Failed to download from GCS for processing: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fast upload: GCS + metadata only, no AI analysis. Returns immediately.
     */
    public ImageUploadDto uploadImageFast(String originalFilename, String contentType, long size, byte[] bytes, Long projectId, String uploaderEmail) {
        return uploadImageFast(originalFilename, contentType, size, bytes, projectId, uploaderEmail, null);
    }

    public ImageUploadDto uploadImageFast(String originalFilename, String contentType, long size, byte[] bytes, Long projectId, String uploaderEmail, Long batchId) {
        User uploader = userRepository.findByEmail(uploaderEmail).orElse(null);
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;
        return uploadToGcsAndSave(originalFilename, contentType, size, bytes, project, uploader, batchId);
    }

    /**
     * Fast upload from a temp file (streaming) — for large files that don't fit in memory.
     */
    public ImageUploadDto uploadImageFastFromFile(String originalFilename, String contentType, long size, java.io.File tempFile, Long projectId, String uploaderEmail) {
        return uploadImageFastFromFile(originalFilename, contentType, size, tempFile, projectId, uploaderEmail, null);
    }

    public ImageUploadDto uploadImageFastFromFile(String originalFilename, String contentType, long size, java.io.File tempFile, Long projectId, String uploaderEmail, Long batchId) {
        User uploader = userRepository.findByEmail(uploaderEmail).orElse(null);
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;

        String fileName = UUID.randomUUID() + "_" + originalFilename;
        try (java.io.InputStream is = new java.io.FileInputStream(tempFile)) {
            uploadToGcsStreaming(fileName, is, contentType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload large file: " + originalFilename, e);
        }

        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + fileName;
        ImageUpload imageUpload;
        synchronized (versionLockFor(originalFilename, batchId)) {
            long[] ver = resolveVersion(originalFilename, batchId);
            imageUpload = ImageUpload.builder()
                    .fileName(originalFilename)
                    .gcsPath("gs://" + bucketName + "/" + fileName)
                    .publicUrl(publicUrl)
                    .contentType(contentType)
                    .fileSize(size)
                    .project(project)
                    .uploadedBy(uploader)
                    .createdAt(LocalDateTime.now())
                    .versionNumber((int) ver[0])
                    .originalUploadId(ver[1] < 0 ? null : ver[1])
                    .build();
            imageUpload = imageUploadRepository.save(imageUpload);
        }
        return toDto(imageUpload);
    }

    /**
     * Run AI analysis (Vision API + Gemini) on an already-uploaded image.
     */
    public void analyzeImageDeferred(Long imageUploadId, byte[] bytes) {
        ImageUpload imageUpload = imageUploadRepository.findById(imageUploadId).orElse(null);
        if (imageUpload == null) return;

        // Skip AI tagging and QC for non-image content types
        String ct = imageUpload.getContentType() != null ? imageUpload.getContentType() : "";
        if (!ct.startsWith("image/")) return;

        // Eagerly fetch project to avoid LazyInitializationException in async context
        Long projectId = imageUpload.getProject() != null ? imageUpload.getProject().getId() : null;
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;

        try {
            List<ImageTag> tags = analyzeImage(bytes, imageUpload, project);
            imageTagRepository.saveAll(tags);
            imageUpload.getTags().addAll(tags);

            if (!tags.isEmpty()) {
                Long batchId = imageUpload.getBatch() != null ? imageUpload.getBatch().getId() : null;
                Long userId = imageUpload.getUploadedBy() != null ? imageUpload.getUploadedBy().getId() : null;
                String tagSummary = tags.stream()
                        .map(t -> t.getCategory() + ":" + t.getValue())
                        .collect(Collectors.joining(", "));
                auditService.log("IMAGE_TAGGING", projectId, batchId, imageUpload.getId(), userId,
                        "AI tagged: " + tagSummary);
            }

            // Run QC for the project's selected marketplaces
            if (project != null && project.getMarketplaces() != null && !project.getMarketplaces().isEmpty()) {
                List<String> marketplaces = List.of(project.getMarketplaces().split(","));
                try {
                    imageQcService.validateForMarketplaces(imageUploadId, marketplaces);
                    log.info("QC completed for image {} on marketplaces: {}", imageUploadId, marketplaces);
                } catch (Exception qcEx) {
                    log.error("QC failed for image {}: {}", imageUploadId, qcEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Deferred AI analysis failed for image {}: {}", imageUploadId, e.getMessage(), e);
        }
    }

    /**
     * Phase 1: Upload to GCS, extract metadata, save entity. No AI calls.
     */
    private ImageUploadDto uploadToGcsAndSave(String originalFilename, String contentType, long size, byte[] bytes, Project project, User uploader) {
        return uploadToGcsAndSave(originalFilename, contentType, size, bytes, project, uploader, null);
    }

    private ImageUploadDto uploadToGcsAndSave(String originalFilename, String contentType, long size, byte[] bytes, Project project, User uploader, Long batchId) {
        String fileName = UUID.randomUUID() + "_" + originalFilename;

        String gcsPath = uploadToGcs(fileName, bytes, contentType);
        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + fileName;

        ImageUpload imageUpload;
        synchronized (versionLockFor(originalFilename, batchId)) {
            long[] ver = resolveVersion(originalFilename, batchId);

            imageUpload = ImageUpload.builder()
                    .fileName(originalFilename)
                    .gcsPath(gcsPath)
                    .publicUrl(publicUrl)
                    .contentType(contentType)
                    .fileSize(size)
                    .project(project)
                    .uploadedBy(uploader)
                    .createdAt(LocalDateTime.now())
                    .versionNumber((int) ver[0])
                    .originalUploadId(ver[1] < 0 ? null : ver[1])
                    .build();
            imageUpload = imageUploadRepository.save(imageUpload);
        }

        Long projectId = project != null ? project.getId() : null;
        Long auditBatchId = imageUpload.getBatch() != null ? imageUpload.getBatch().getId() : null;
        Long userId = uploader != null ? uploader.getId() : null;

        auditService.log("IMAGE_UPLOAD", projectId, auditBatchId, imageUpload.getId(), userId,
                "Uploaded " + originalFilename);

        extractAndSaveMetadata(imageUpload, bytes);

        return toDto(imageUpload);
    }

    private ImageUploadDto processUpload(String originalFilename, String contentType, long size, byte[] bytes, Project project, User uploader) {
        String fileName = UUID.randomUUID() + "_" + originalFilename;

        String gcsPath = uploadToGcs(fileName, bytes, contentType);
        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + fileName;

        ImageUpload imageUpload = ImageUpload.builder()
                .fileName(originalFilename)
                .gcsPath(gcsPath)
                .publicUrl(publicUrl)
                .contentType(contentType)
                .fileSize(size)
                .project(project)
                .uploadedBy(uploader)
                .createdAt(LocalDateTime.now())
                .build();
        imageUpload = imageUploadRepository.save(imageUpload);

        Long projectId = project != null ? project.getId() : null;
        Long batchId = imageUpload.getBatch() != null ? imageUpload.getBatch().getId() : null;
        Long userId = uploader != null ? uploader.getId() : null;

        auditService.log("IMAGE_UPLOAD", projectId, batchId, imageUpload.getId(), userId,
                "Uploaded " + originalFilename);

        extractAndSaveMetadata(imageUpload, bytes);

        // Only run AI tagging and QC for image content types
        String ct = contentType != null ? contentType : "";
        if (ct.startsWith("image/")) {
            List<ImageTag> tags = analyzeImage(bytes, imageUpload, project);
            imageTagRepository.saveAll(tags);
            imageUpload.getTags().addAll(tags);

            if (!tags.isEmpty()) {
                String tagSummary = tags.stream()
                        .map(t -> t.getCategory() + ":" + t.getValue())
                        .collect(Collectors.joining(", "));
                auditService.log("IMAGE_TAGGING", projectId, batchId, imageUpload.getId(), userId,
                        "AI tagged: " + tagSummary);
            }

            // Run QC automatically after tagging for the project's configured marketplaces
            if (project != null && project.getMarketplaces() != null && !project.getMarketplaces().isEmpty()) {
                List<String> marketplaces = List.of(project.getMarketplaces().split(","));
                try {
                    imageQcService.validateForMarketplaces(imageUpload.getId(), marketplaces);
                    log.info("QC completed for image {} on marketplaces: {}", imageUpload.getId(), marketplaces);
                } catch (Exception qcEx) {
                    log.error("QC failed for image {}: {}", imageUpload.getId(), qcEx.getMessage());
                }
            }
        }

        return toDto(imageUpload);
    }

    private void extractAndSaveMetadata(ImageUpload imageUpload, byte[] bytes) {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(bais);
            if (iis != null) {
                java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    javax.imageio.ImageReader reader = readers.next();
                    reader.setInput(iis);
                    imageUpload.setWidth(reader.getWidth(0));
                    imageUpload.setHeight(reader.getHeight(0));
                    try {
                        javax.imageio.metadata.IIOMetadata metadata = reader.getImageMetadata(0);
                        if (metadata != null) {
                            org.w3c.dom.Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
                            extractDpiFromMetadata(imageUpload, root);
                        }
                    } catch (Exception ignore) {}
                    try {
                        java.awt.image.BufferedImage img = reader.read(0);
                        if (img != null) {
                            java.awt.color.ColorSpace cs = img.getColorModel().getColorSpace();
                            if (cs.getType() == java.awt.color.ColorSpace.TYPE_RGB) {
                                imageUpload.setColorSpace(cs.isCS_sRGB() ? "sRGB" : "RGB");
                            } else if (cs.getType() == java.awt.color.ColorSpace.TYPE_CMYK) {
                                imageUpload.setColorSpace("CMYK");
                            } else if (cs.getType() == java.awt.color.ColorSpace.TYPE_GRAY) {
                                imageUpload.setColorSpace("Grayscale");
                            }

                            // Generate JPEG preview for non-browser-renderable formats
                            if (needsPreview(imageUpload.getFileName())) {
                                try {
                                    String previewUrl = generateAndUploadPreview(img, imageUpload);
                                    if (previewUrl != null) {
                                        imageUpload.setPreviewUrl(previewUrl);
                                        log.info("Generated preview for {}: {}", imageUpload.getFileName(), previewUrl);
                                    }
                                } catch (Exception previewEx) {
                                    log.warn("Failed to generate preview for {}: {}", imageUpload.getFileName(), previewEx.getMessage());
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                    reader.dispose();
                }
                iis.close();
            }
        } catch (Exception ignore) {}

        // Fallback: for RAW files (CR3, CR2, NEF, ARW) that ImageIO can't read,
        // extract the embedded JPEG preview using metadata-extractor
        if (imageUpload.getPreviewUrl() == null && needsPreview(imageUpload.getFileName())) {
            try {
                String previewUrl = extractEmbeddedPreview(bytes, imageUpload);
                if (previewUrl != null) {
                    imageUpload.setPreviewUrl(previewUrl);
                    log.info("Extracted embedded preview for {}: {}", imageUpload.getFileName(), previewUrl);
                }
            } catch (Exception e) {
                log.warn("Failed to extract embedded preview for {}: {}", imageUpload.getFileName(), e.getMessage());
            }
        }

        imageUploadRepository.save(imageUpload);
    }

    // ── Preview generation ─────────────────────────────────────────────────────

    /** Extensions that browsers cannot render natively — need a JPEG preview. */
    private static final Set<String> NEEDS_PREVIEW_EXTS = Set.of(
            "psd", "ai", "eps", "tif", "tiff", "raw", "cr2", "cr3", "nef", "arw", "dng", "heic", "heif"
    );

    private boolean needsPreview(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return NEEDS_PREVIEW_EXTS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    /**
     * Converts a BufferedImage to JPEG, uploads to GCS as a preview, and returns the public URL.
     */
    private String generateAndUploadPreview(java.awt.image.BufferedImage img, ImageUpload imageUpload) {
        try {
            // Convert CMYK or other color models to sRGB for JPEG output
            java.awt.image.BufferedImage rgbImage = new java.awt.image.BufferedImage(
                    img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgbImage.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();

            // Scale down if very large (max 2000px on longest side)
            int maxDim = 2000;
            int w = rgbImage.getWidth(), h = rgbImage.getHeight();
            if (w > maxDim || h > maxDim) {
                double scale = (double) maxDim / Math.max(w, h);
                int nw = (int) (w * scale), nh = (int) (h * scale);
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(rgbImage, 0, 0, nw, nh, null);
                g2.dispose();
                rgbImage = scaled;
            }

            // Write JPEG
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(rgbImage, "jpg", baos);
            byte[] jpegBytes = baos.toByteArray();

            // Upload to GCS under previews/ prefix
            String previewFileName = "previews/" + imageUpload.getId() + "_preview.jpg";
            uploadToGcs(previewFileName, jpegBytes, "image/jpeg");
            return "https://storage.googleapis.com/" + bucketName + "/" + previewFileName;
        } catch (Exception e) {
            log.warn("Preview generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the embedded JPEG preview from RAW camera files (CR3, CR2, NEF, ARW, DNG)
     * using metadata-extractor. Most RAW files contain a full-size or half-size JPEG preview.
     */
    private String extractEmbeddedPreview(byte[] rawBytes, ImageUpload imageUpload) {
        try {
            com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(
                    new java.io.ByteArrayInputStream(rawBytes));

            // Look for JPEG preview in Exif thumbnail or preview image tags
            for (com.drew.metadata.Directory dir : metadata.getDirectories()) {
                // Check for large preview in Exif IFD
                // TAG IDs: JPEGInterchangeFormat=0x0201, JPEGInterchangeFormatLength=0x0202
                if (dir.containsTag(0x0201) && dir.containsTag(0x0202)) {
                    int offset = dir.getInt(0x0201);
                    int length = dir.getInt(0x0202);
                    if (offset > 0 && length > 1000 && offset + length <= rawBytes.length) {
                        byte[] jpegBytes = java.util.Arrays.copyOfRange(rawBytes, offset, offset + length);
                        // Verify it's a valid JPEG (starts with FF D8)
                        if (jpegBytes.length > 2 && (jpegBytes[0] & 0xFF) == 0xFF && (jpegBytes[1] & 0xFF) == 0xD8) {
                            String previewFileName = "previews/" + imageUpload.getId() + "_preview.jpg";
                            uploadToGcs(previewFileName, jpegBytes, "image/jpeg");

                            // Also extract dimensions from the preview
                            try {
                                java.awt.image.BufferedImage previewImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpegBytes));
                                if (previewImg != null) {
                                    imageUpload.setWidth(previewImg.getWidth());
                                    imageUpload.setHeight(previewImg.getHeight());
                                }
                            } catch (Exception ignore) {}

                            return "https://storage.googleapis.com/" + bucketName + "/" + previewFileName;
                        }
                    }
                }
            }

            // Fallback: scan for largest JPEG segment in the raw file
            int bestOffset = -1, bestLength = 0;
            for (int i = 0; i < rawBytes.length - 3; i++) {
                if ((rawBytes[i] & 0xFF) == 0xFF && (rawBytes[i + 1] & 0xFF) == 0xD8) {
                    // Found JPEG SOI marker, scan for EOI
                    for (int j = i + 2; j < rawBytes.length - 1; j++) {
                        if ((rawBytes[j] & 0xFF) == 0xFF && (rawBytes[j + 1] & 0xFF) == 0xD9) {
                            int len = j - i + 2;
                            if (len > bestLength && len > 10000) { // Only consider JEPGs > 10KB
                                bestOffset = i;
                                bestLength = len;
                            }
                            break;
                        }
                    }
                }
            }
            if (bestOffset >= 0) {
                byte[] jpegBytes = java.util.Arrays.copyOfRange(rawBytes, bestOffset, bestOffset + bestLength);
                String previewFileName = "previews/" + imageUpload.getId() + "_preview.jpg";
                uploadToGcs(previewFileName, jpegBytes, "image/jpeg");
                log.info("Extracted JPEG preview from RAW scan: {} bytes at offset {}", bestLength, bestOffset);
                return "https://storage.googleapis.com/" + bucketName + "/" + previewFileName;
            }

            log.warn("No embedded JPEG preview found in {}", imageUpload.getFileName());
            return null;
        } catch (Exception e) {
            log.warn("Failed to extract embedded preview: {}", e.getMessage());
            return null;
        }
    }

    // ── GCS upload ────────────────────────────────────────────────────────────

    /** Threshold above which we use resumable (chunked) upload instead of single-shot. */
    private static final int RESUMABLE_THRESHOLD = 10 * 1024 * 1024; // 10 MB
    /** Chunk size for resumable uploads (8 MB — must be multiple of 256 KB). */
    private static final int UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024;

    private String uploadToGcs(String fileName, byte[] bytes, String contentType) {
        Storage storage = StorageOptions.getDefaultInstance().getService();
        BlobId blobId = BlobId.of(bucketName, fileName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        if (bytes.length <= RESUMABLE_THRESHOLD) {
            // Small files: single-shot upload
            storage.create(blobInfo, bytes);
        } else {
            // Large files: resumable upload with chunked writes
            log.info("Using resumable upload for {} ({} bytes)", fileName, bytes.length);
            try (var writer = storage.writer(blobInfo)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int chunkLen = Math.min(UPLOAD_CHUNK_SIZE, bytes.length - offset);
                    writer.write(java.nio.ByteBuffer.wrap(bytes, offset, chunkLen));
                    offset += chunkLen;
                }
            } catch (Exception e) {
                throw new RuntimeException("Resumable upload failed for " + fileName, e);
            }
        }
        return "gs://" + bucketName + "/" + fileName;
    }

    /**
     * Streaming upload from InputStream — avoids loading the entire file into memory.
     */
    private String uploadToGcsStreaming(String fileName, java.io.InputStream inputStream, String contentType) {
        Storage storage = StorageOptions.getDefaultInstance().getService();
        BlobId blobId = BlobId.of(bucketName, fileName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType != null ? contentType : "application/octet-stream")
                .build();
        try (var writer = storage.writer(blobInfo)) {
            byte[] buffer = new byte[UPLOAD_CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                writer.write(java.nio.ByteBuffer.wrap(buffer, 0, bytesRead));
            }
        } catch (Exception e) {
            throw new RuntimeException("Streaming upload failed for " + fileName, e);
        }
        return "gs://" + bucketName + "/" + fileName;
    }

    // ── Image analysis ─────────────────────────────────────────────────────────

    private List<ImageTag> analyzeImage(byte[] imageBytes, ImageUpload imageUpload, Project project) {
        List<ImageTag> tags = new ArrayList<>();

        // 1) Vision API — face detection + grouping, color extraction
        try (ImageAnnotatorClient client = ImageAnnotatorClient.create()) {
            Image image = Image.newBuilder()
                    .setContent(ByteString.copyFrom(imageBytes))
                    .build();

            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .setImage(image)
                    .addFeatures(Feature.newBuilder().setType(Feature.Type.FACE_DETECTION).setMaxResults(10))
                    .addFeatures(Feature.newBuilder().setType(Feature.Type.IMAGE_PROPERTIES))
                    .build();

            BatchAnnotateImagesResponse response = client.batchAnnotateImages(List.of(request));
            AnnotateImageResponse result = response.getResponses(0);

            if (result.hasError()) {
                log.error("Vision API error: {}", result.getError().getMessage());
            } else {
                log.info("Faces detected for {}: {}", imageUpload.getFileName(), result.getFaceAnnotationsCount());
                tags.addAll(extractColorTags(result, imageUpload));
                tags.addAll(extractFaceTags(result, imageUpload, project, imageBytes));
            }
        } catch (Exception e) {
            log.error("Vision API analysis failed: {}", e.getMessage(), e);
        }

        // 2) Gemini — gender, age, pattern, and clothing tags
        tags.addAll(analyzeWithGemini(imageBytes, imageUpload));

        return deduplicateTags(tags);
    }

    // ── Gemini (Vertex AI) analysis ──────────────────────────────────────────

    private static final String GEMINI_PROMPT = """
            Analyze this image and return ONLY a comma-separated list of tags in category:value format.

            Required categories (return exactly one value per category ONLY if the person's face is clearly visible — skip entirely for back-facing, detail, or faceless shots):
            - gender: Male or Female
            - age: Baby, Child, Teenager, Adult, or Senior

            Required category (always return exactly one value):
            - angle: the photo angle. If a person is visible:
              Front (face is facing the camera), Left (face or body turned to the left),
              Right (face or body turned to the right), Back (person's back faces the camera),
              Detail (head is not clearly visible), Top (top-down angle).
              If no person is visible and only a product/accessory is shown:
              Front (product logo is visible), Back (product logo is not visible).

            Optional categories (include all that apply — return as multiple separate tags for multi-value categories, e.g. accessory:Watch,accessory:Necklace):
            - pattern: the clothing pattern (e.g. Striped, Plaid, Floral, Solid, Checked, Geometric, Abstract, Camouflage, Polka Dot)
            - clothing: specific garment types (e.g. Dress, Jeans, Jacket, T-Shirt, Sweater, Skirt, Coat, Shorts)
            - material: the fabric or material type (e.g. Denim, Cotton, Silk, Leather, Wool, Polyester, Linen, Suede, Velvet, Nylon)
            - accessory: accessories the model is wearing or that are visible on/with the model. Return each accessory as a SEPARATE tag (one per visible item).
              Examples: Watch, Necklace, Earrings, Ring, Bracelet, Bangle, Anklet, Pendant, Choker, Brooch, Cufflinks,
              Handbag, Clutch, Tote, Backpack, Belt, Hat, Cap, Beanie, Fedora, Scarf, Tie, Bow Tie, Pocket Square,
              Sunglasses, Glasses, Gloves, Watch Strap, Headband, Hair Clip, Hairband, Shoes, Sneakers, Heels, Boots, Sandals.
              Be specific (use "Necklace" not "Jewelry"). If a single category appears multiple times (e.g. multiple rings),
              return it once. Omit the category entirely if no accessories are visible.
            - background: the background type, exactly one value (e.g. White, Black, Grey, Studio, Outdoor, Indoor, Gradient, Plain)

            Required SEO categories (always return exactly one value each):
            - image_title: a concise, descriptive title for the image suitable for ecommerce (max 80 chars). Include product type, color, and view angle.
            - alt_text: SEO-optimized alt text for accessibility and search engines (max 200 chars). CRITICAL: Describe ONLY the garment/product itself. Never use phrases like "woman wearing", "man in", "model wearing", "person in", "close-up of a woman", or any reference to a human. Write as if describing the product on a hanger or mannequin.
            - description: a detailed product description for ecommerce listing (max 500 chars). CRITICAL: Describe ONLY the garment/product — its features, fabric, fit, styling, color, and construction details. Never mention or reference any person, model, wearer, or human body. Write as a product catalog description.

            Rules:
            - Return ONLY the tag list, no explanation or extra text
            - Use Title Case for tag values (except image_title, alt_text, description which use natural sentence case)
            - Do NOT return gender or age tags when the angle is Back, Detail, or Top — the face must be clearly visible to determine gender and age
            - If no person is visible, return only angle, pattern, clothing, material, accessory, background, image_title, alt_text and description tags as applicable
            - CRITICAL: In alt_text and description, NEVER use words like "woman", "man", "model", "person", "wearing", "worn by", "dressed in", or any human reference. Describe ONLY the product.
            - Example output: gender:Female,age:Adult,angle:Front,pattern:Floral,clothing:Dress,material:Cotton,accessory:Necklace,accessory:Earrings,accessory:Handbag,background:White,image_title:Floral Cotton Dress - Front View,alt_text:Floral cotton dress with relaxed fit and rounded neckline in front view against white studio background,description:Elegant floral cotton dress featuring a vibrant pattern with a relaxed fit and rounded neckline. Lightweight cotton fabric with soft drape ideal for warm weather. Photographed on clean white studio background.
            """;

    private List<ImageTag> analyzeWithGemini(byte[] imageBytes, ImageUpload imageUpload) {
        List<ImageTag> tags = new ArrayList<>();
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String mimeType = imageUpload.getContentType() != null ? imageUpload.getContentType() : "image/jpeg";

            // Build Gemini API request JSON
            String requestBody = new ObjectMapper().writeValueAsString(Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("inline_data", Map.of(
                                            "mime_type", mimeType,
                                            "data", base64Image
                                    )),
                                    Map.of("text", GEMINI_PROMPT)
                            )
                    ))
            ));

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + geminiModel + ":generateContent?key=" + geminiApiKey;

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(60))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API returned status {}: {}", response.statusCode(), response.body());
                return tags;
            }

            // Parse response JSON
            JsonNode root = new ObjectMapper().readTree(response.body());
            String text = root.path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text").asText("").trim();
            log.info("Gemini tags for {}: {}", imageUpload.getFileName(), text);

            // Parse the comma-separated response, handling commas inside description values
            // by tracking known categories
            Set<String> tagCategories = Set.of("gender", "age", "angle", "pattern", "clothing", "material", "accessory", "background");
            Set<String> fieldCategories = Set.of("image_title", "alt_text", "description");
            Set<String> allCategories = new HashSet<>();
            allCategories.addAll(tagCategories);
            allCategories.addAll(fieldCategories);

            // Split intelligently: split on comma only when followed by a known category
            String[] rawPairs = text.split(",(?=\\s*(?:gender|age|angle|pattern|clothing|material|accessory|background|image_title|alt_text|description):)");
            for (String pair : rawPairs) {
                pair = pair.trim();
                int colon = pair.indexOf(':');
                if (colon <= 0 || colon >= pair.length() - 1) continue;
                String category = pair.substring(0, colon).trim().toLowerCase();
                String value = pair.substring(colon + 1).trim();
                if (category.isEmpty() || value.isEmpty()) continue;

                if (tagCategories.contains(category)) {
                    tags.add(tag(imageUpload, category, value, 0.90));
                } else if ("image_title".equals(category)) {
                    imageUpload.setImageTitle(value);
                } else if ("alt_text".equals(category)) {
                    imageUpload.setAltText(value);
                } else if ("description".equals(category)) {
                    imageUpload.setDescription(value);
                }
            }
            // Persist the SEO fields
            imageUploadRepository.save(imageUpload);
        } catch (Exception e) {
            log.error("Gemini analysis failed for {}: {}", imageUpload.getFileName(), e.getMessage(), e);
        }
        return tags;
    }

    /** Remove duplicate tags (same category + value, case-insensitive) — keep the one with highest confidence. */
    private List<ImageTag> deduplicateTags(List<ImageTag> tags) {
        Map<String, ImageTag> best = new LinkedHashMap<>();
        for (ImageTag t : tags) {
            String key = t.getCategory().toLowerCase() + ":" + t.getValue().toLowerCase();
            ImageTag existing = best.get(key);
            if (existing == null || t.getConfidence() > existing.getConfidence()) {
                best.put(key, t);
            }
        }
        return new ArrayList<>(best.values());
    }

    // ── Color extraction ─────────────────────────────────────────────────────

    private List<ImageTag> extractColorTags(AnnotateImageResponse result, ImageUpload imageUpload) {
        List<ImageTag> tags = new ArrayList<>();
        if (!result.hasImagePropertiesAnnotation()) return tags;

        result.getImagePropertiesAnnotation().getDominantColors()
                .getColorsList().stream()
                .sorted(Comparator.comparingDouble(ColorInfo::getPixelFraction).reversed())
                .limit(3)
                .forEach(ci -> {
                    com.google.type.Color c = ci.getColor();
                    String name = rgbToColorName((int) c.getRed(), (int) c.getGreen(), (int) c.getBlue());
                    tags.add(tag(imageUpload, "color", name, (double) ci.getPixelFraction()));
                });
        return tags;
    }

    /** Simple HSV-style RGB-to-name mapping. */
    private String rgbToColorName(int r, int g, int b) {
        if (r < 50 && g < 50 && b < 50) return "Black";
        if (r > 200 && g > 200 && b > 200) return "White";
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min < 30) return "Gray";
        if (max == r) {
            if (g > 100 && b < 80) return "Orange";
            if (b > 100 && g < 80) return "Pink";
            return "Red";
        }
        if (max == g) return b > 100 ? "Cyan" : "Green";
        return r > 100 ? "Purple" : "Blue";
    }

    // ── Face detection + grouping ─────────────────────────────────────────────

    /** Minimum detection confidence for a face to be tagged. */
    private static final float MIN_FACE_CONFIDENCE = 0.75f;

    /** Maximum absolute pan angle (left-right head turn) — beyond this the face is not visible enough. */
    private static final float MAX_PAN_ANGLE = 55.0f;

    /** Maximum absolute tilt angle (up-down head tilt) — beyond this the face is not visible enough. */
    private static final float MAX_TILT_ANGLE = 45.0f;

    private List<ImageTag> extractFaceTags(AnnotateImageResponse result, ImageUpload imageUpload, Project project, byte[] imageBytes) {
        List<ImageTag> tags = new ArrayList<>();
        List<FaceAnnotation> faces = result.getFaceAnnotationsList();
        if (faces.isEmpty()) return tags;

        // Load existing face groups scoped to the same project (or global if no project)
        List<FaceGroup> groups = project != null
                ? faceGroupRepository.findByProjectId(project.getId())
                : faceGroupRepository.findByProjectIsNull();

        // Read the source image once for face cropping
        BufferedImage sourceImage = null;
        try {
            sourceImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            log.warn("Could not decode image for face cropping: {}", e.getMessage());
        }

        for (FaceAnnotation face : faces) {
            float confidence = face.getDetectionConfidence();
            float panAngle = face.getPanAngle();
            float tiltAngle = face.getTiltAngle();
            float rollAngle = face.getRollAngle();

            log.info("Face detected: confidence={}, pan={}, tilt={}, roll={}, joy={}, sorrow={}, anger={}, surprise={}, blur={}, underExposed={}, headwear={}",
                    confidence, panAngle, tiltAngle, rollAngle,
                    face.getJoyLikelihood(), face.getSorrowLikelihood(),
                    face.getAngerLikelihood(), face.getSurpriseLikelihood(),
                    face.getBlurredLikelihood(), face.getUnderExposedLikelihood(),
                    face.getHeadwearLikelihood());

            // Skip low-confidence detections
            if (confidence < MIN_FACE_CONFIDENCE) {
                log.info("Skipping face: low confidence {} (need {})", confidence, MIN_FACE_CONFIDENCE);
                continue;
            }

            // Skip blurred faces
            if (face.getBlurredLikelihood().getNumber() >= Likelihood.LIKELY.getNumber()) {
                log.info("Skipping face: blurred ({})", face.getBlurredLikelihood());
                continue;
            }

            // Skip under-exposed faces
            if (face.getUnderExposedLikelihood().getNumber() >= Likelihood.VERY_LIKELY.getNumber()) {
                log.info("Skipping face: under-exposed ({})", face.getUnderExposedLikelihood());
                continue;
            }

            // Skip faces turned too far away (back-facing, extreme profile)
            if (Math.abs(panAngle) > MAX_PAN_ANGLE) {
                log.info("Skipping face: pan angle {} exceeds max {} (face turned too far)", panAngle, MAX_PAN_ANGLE);
                continue;
            }

            // Skip faces tilted too far (looking up/down extreme)
            if (Math.abs(tiltAngle) > MAX_TILT_ANGLE) {
                log.info("Skipping face: tilt angle {} exceeds max {} (face tilted too far)", tiltAngle, MAX_TILT_ANGLE);
                continue;
            }

            // Crop the face from the source image
            byte[] faceCropBytes = cropFaceToBytes(face, sourceImage);
            if (faceCropBytes == null) continue;

            FaceGroup matched = findOrCreateFaceGroup(faceCropBytes, groups, project, face, sourceImage);
            if (matched == null) continue; // matching unavailable — leave the face untagged rather than mislabel
            tags.add(tag(imageUpload, "face_id", matched.getGroupLabel(), (double) confidence));
        }
        return tags;
    }

    /**
     * Crops a face from the source image and returns JPEG bytes (for Gemini comparison).
     */
    private byte[] cropFaceToBytes(FaceAnnotation face, BufferedImage sourceImage) {
        if (sourceImage == null) return null;
        try {
            BoundingPoly bb = face.getFdBoundingPoly();
            if (bb.getVerticesCount() < 4) return null;

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = 0, maxY = 0;
            for (Vertex v : bb.getVerticesList()) {
                minX = Math.min(minX, v.getX());
                minY = Math.min(minY, v.getY());
                maxX = Math.max(maxX, v.getX());
                maxY = Math.max(maxY, v.getY());
            }

            int w = maxX - minX, h = maxY - minY;
            int padX = (int) (w * 0.20), padY = (int) (h * 0.20);
            int cropX = Math.max(0, minX - padX);
            int cropY = Math.max(0, minY - padY);
            int cropW = Math.min(sourceImage.getWidth() - cropX, w + 2 * padX);
            int cropH = Math.min(sourceImage.getHeight() - cropY, h + 2 * padY);
            if (cropW <= 0 || cropH <= 0) return null;

            // Square crop
            int side = Math.max(cropW, cropH);
            int centerX = cropX + cropW / 2, centerY = cropY + cropH / 2;
            int squareX = Math.max(0, centerX - side / 2);
            int squareY = Math.max(0, centerY - side / 2);
            int squareSide = Math.min(side, Math.min(sourceImage.getWidth() - squareX, sourceImage.getHeight() - squareY));

            BufferedImage cropped = sourceImage.getSubimage(squareX, squareY, squareSide, squareSide);

            // Resize to 256x256
            BufferedImage thumbnail = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = thumbnail.createGraphics();
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(cropped, 0, 0, 256, 256, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to crop face: {}", e.getMessage());
            return null;
        }
    }

    private FaceGroup findOrCreateFaceGroup(byte[] faceCropBytes, List<FaceGroup> groups, Project project,
                                            FaceAnnotation face, BufferedImage sourceImage) {
        // Serialize match-or-create across concurrent Phase 2 tasks for the same project so we
        // don't end up creating two person_N rows for the same person from different images.
        Long lockKey = project != null ? project.getId() : -1L;
        Object lock = faceGroupLocks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            // Reload inside the lock — another thread may have just created a new group that matches.
            List<FaceGroup> latest = project != null
                    ? faceGroupRepository.findByProjectId(project.getId())
                    : faceGroupRepository.findByProjectIsNull();

            if (!latest.isEmpty()) {
                try {
                    String matchedLabel = matchFaceWithGemini(faceCropBytes, latest);
                    if (matchedLabel != null) {
                        for (FaceGroup g : latest) {
                            if (g.getGroupLabel().equals(matchedLabel)) {
                                log.info("Gemini face matched to {}", matchedLabel);
                                return g;
                            }
                        }
                    }
                } catch (FaceMatchUnavailableException e) {
                    // Don't fall through to "create new group" — that would proliferate spurious
                    // person_N rows whenever the Gemini API is unhappy. Skip face tagging instead.
                    log.warn("Skipping face — Gemini matching unavailable: {}", e.getMessage());
                    return null;
                }
            }

            log.info("New face detected — no Gemini match found among {} existing groups", latest.size());
            String thumbnailUrl = uploadFaceThumbnail(faceCropBytes);
            String label = "person_" + (latest.size() + 1);
            FaceGroup newGroup = FaceGroup.builder()
                    .groupLabel(label)
                    .landmarkFingerprint("[]") // no longer used for matching
                    .faceThumbnailUrl(thumbnailUrl)
                    .project(project)
                    .createdAt(LocalDateTime.now())
                    .build();
            return faceGroupRepository.save(newGroup);
        }
    }

    private String uploadFaceThumbnail(byte[] thumbBytes) {
        try {
            String fileName = "face_thumbs/" + UUID.randomUUID() + ".jpg";
            uploadToGcs(fileName, thumbBytes, "image/jpeg");
            return "https://storage.googleapis.com/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            log.warn("Failed to upload face thumbnail: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Thrown when Gemini face matching couldn't complete (rate limit exhausted, network failure,
     * etc.). Caller should skip face tagging rather than create a spurious new face group.
     */
    private static class FaceMatchUnavailableException extends RuntimeException {
        FaceMatchUnavailableException(String msg) { super(msg); }
        FaceMatchUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Parses the {@code retryDelay} field from a Gemini 429 error body (e.g. {@code "29s"} or
     * {@code "1.5s"}) into milliseconds. Returns empty if the field isn't present or parseable.
     */
    private java.util.Optional<Long> parseGeminiRetryDelayMs(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            for (JsonNode detail : root.path("error").path("details")) {
                String type = detail.path("@type").asText("");
                if (type.endsWith("RetryInfo")) {
                    String s = detail.path("retryDelay").asText("");
                    if (s.endsWith("s")) {
                        double seconds = Double.parseDouble(s.substring(0, s.length() - 1));
                        return java.util.Optional.of((long) (seconds * 1000));
                    }
                }
            }
        } catch (Exception ignored) {}
        return java.util.Optional.empty();
    }

    /**
     * Uses Gemini to visually compare a face crop against existing face group thumbnails.
     * Returns the matching group label, or null if Gemini explicitly says NEW.
     * Throws {@link FaceMatchUnavailableException} on exhausted retries / unrecoverable errors —
     * callers must NOT treat that as "no match", or every face will become a new group when the
     * API is rate-limited.
     */
    private String matchFaceWithGemini(byte[] faceCropBytes, List<FaceGroup> groups) {
        try {
            // Build list of reference faces with their labels — download thumbnails from GCS
            List<Map<String, Object>> parts = new ArrayList<>();

            // First part: the new face crop
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(faceCropBytes)
            )));
            parts.add(Map.of("text", "This is the NEW FACE to identify.\n\nBelow are the existing known faces with their labels:\n"));

            // Add each existing face group thumbnail (cached across calls — see faceThumbnailCache)
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
            List<String> validLabels = new ArrayList<>();

            for (FaceGroup g : groups) {
                String thumbUrl = g.getFaceThumbnailUrl();
                if (thumbUrl == null || thumbUrl.isBlank()) continue;

                byte[] thumbBytes = faceThumbnailCache.computeIfAbsent(thumbUrl,
                        u -> downloadImage(httpClient, u));
                if (thumbBytes == null) {
                    faceThumbnailCache.remove(thumbUrl); // don't cache failed downloads
                    continue;
                }

                parts.add(Map.of("inline_data", Map.of(
                        "mime_type", "image/jpeg",
                        "data", Base64.getEncoder().encodeToString(thumbBytes)
                )));
                parts.add(Map.of("text", "Label: " + g.getGroupLabel()));
                validLabels.add(g.getGroupLabel());
            }

            if (validLabels.isEmpty()) return null;

            // Final prompt — strict: only match when bone structure is a clear positive ID.
            // Bias toward NEW to avoid merging distinct people into the same group.
            parts.add(Map.of("text",
                    "\nYou are a strict face identity verifier. Your job is to decide whether the NEW FACE (first image) " +
                    "is the EXACT SAME INDIVIDUAL as one of the labeled faces shown above.\n\n" +
                    "DEFAULT ANSWER IS NEW. Only return a label if you are highly confident (≥90%) it is the same person.\n\n" +
                    "Compare these stable bone-structure features:\n" +
                    "- Eye shape and inter-ocular spacing\n" +
                    "- Nose shape, width, and bridge profile\n" +
                    "- Lip shape and philtrum\n" +
                    "- Jawline, chin shape, and overall facial geometry\n" +
                    "- Cheekbone position and prominence\n\n" +
                    "Immediate NEW — return NEW if ANY of these differ:\n" +
                    "- Apparent gender differs\n" +
                    "- Age group differs (child / teen / adult / senior)\n" +
                    "- Ethnicity or skin tone differs\n" +
                    "- Bone structure geometry looks like a different individual (even same gender/age/ethnicity)\n\n" +
                    "Ignore for matching purposes (these do NOT distinguish people):\n" +
                    "- Pose, angle, profile vs frontal\n" +
                    "- Lighting, shadows, exposure\n" +
                    "- Expression, smile, open/closed eyes\n" +
                    "- Hair, makeup, glasses, jewelry, clothing\n\n" +
                    "Decision rule:\n" +
                    "- Return the LABEL only when you are ≥90% certain the bone structure is the same individual.\n" +
                    "- When uncertain, return NEW — it is far better to create an extra group than to merge different people.\n" +
                    "- If multiple labeled faces share similar demographics, compare bone structure carefully; return NEW unless one is clearly the same person.\n\n" +
                    "Respond with EXACTLY one of: a label like person_3, or the word NEW. No other text."
            ));

            String requestBody = new ObjectMapper().writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", parts))
            ));

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + geminiModel + ":generateContent?key=" + geminiApiKey;

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // Retry on 429 / 5xx with backoff. Two attempts total — daily-quota errors won't
            // recover within a request, so we don't burn the thread waiting forever; the caller
            // skips face tagging on exhaustion.
            final int maxAttempts = 2;
            java.net.http.HttpResponse<String> response = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) break;

                boolean retryable = (status == 429 || status >= 500);
                log.error("Gemini face match API returned status {}: {}", status, response.body());

                if (!retryable || attempt == maxAttempts) {
                    throw new FaceMatchUnavailableException("Gemini matching failed: HTTP " + status);
                }

                long delayMs = parseGeminiRetryDelayMs(response.body()).orElse(2000L * attempt);
                delayMs = Math.min(delayMs, 30_000L);
                log.info("Retrying Gemini face match in {} ms (attempt {}/{})", delayMs, attempt + 1, maxAttempts);
                Thread.sleep(delayMs);
            }

            JsonNode root = new ObjectMapper().readTree(response.body());
            String answer = root.path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text").asText("").trim();
            log.info("Gemini face match result: {}", answer);

            if (validLabels.contains(answer)) {
                return answer;
            }
            return null;

        } catch (FaceMatchUnavailableException ge) {
            throw ge;
        } catch (Exception e) {
            log.error("Gemini face matching failed: {}", e.getMessage(), e);
            throw new FaceMatchUnavailableException("Gemini matching error: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads an image from a URL and returns its bytes.
     */
    private byte[] downloadImage(java.net.http.HttpClient httpClient, String imageUrl) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(imageUrl))
                    .GET()
                    .build();
            java.net.http.HttpResponse<byte[]> response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("Failed to download image {}: status {}", imageUrl, response.statusCode());
            return null;
        } catch (Exception e) {
            log.warn("Failed to download image {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ImageTag tag(ImageUpload upload, String category, String value, double confidence) {
        return ImageTag.builder()
                .imageUpload(upload)
                .category(category)
                .value(value)
                .confidence(confidence)
                .build();
    }

    private void extractDpiFromMetadata(ImageUpload upload, org.w3c.dom.Node node) {
        if (node == null) return;
        String name = node.getNodeName();
        if ("app0JFIF".equals(name) || "JPEGvariety".equals(name)) {
            org.w3c.dom.NamedNodeMap attrs = node.getAttributes();
            if (attrs != null) {
                org.w3c.dom.Node xDens = attrs.getNamedItem("Xdensity");
                org.w3c.dom.Node yDens = attrs.getNamedItem("Ydensity");
                if (xDens != null) try { upload.setDpiX(Integer.parseInt(xDens.getNodeValue())); } catch (NumberFormatException ignore) {}
                if (yDens != null) try { upload.setDpiY(Integer.parseInt(yDens.getNodeValue())); } catch (NumberFormatException ignore) {}
            }
        }
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            extractDpiFromMetadata(upload, children.item(i));
        }
    }

    private ImageUploadDto toDto(ImageUpload u) {
        List<ImageTagDto> tagDtos = u.getTags().stream()
                .map(t -> new ImageTagDto(t.getId(), t.getCategory(), t.getValue(), t.getConfidence()))
                .collect(Collectors.toList());
        Long projectId = null;
        Long batchId = null;
        String uploaderEmail = null;
        try { projectId = u.getProject() != null ? u.getProject().getId() : null; } catch (Exception ignore) {}
        try { batchId = u.getBatch() != null ? u.getBatch().getId() : null; } catch (Exception ignore) {}
        try { uploaderEmail = u.getUploadedBy() != null ? u.getUploadedBy().getEmail() : null; } catch (Exception ignore) {}
        return new ImageUploadDto(
                u.getId(),
                u.getFileName(),
                u.getPublicUrl(),
                u.getContentType(),
                u.getFileSize(),
                projectId,
                batchId,
                uploaderEmail,
                u.getCreatedAt(),
                tagDtos,
                u.getWidth(),
                u.getHeight(),
                u.getColorSpace(),
                u.getDpiX(),
                u.getDpiY(),
                u.getImageQualityQcCheck(),
                u.getImageTitle(),
                u.getAltText(),
                u.getDescription(),
                u.getPreviewUrl(),
                u.getVersionNumber() != null ? u.getVersionNumber() : 1,
                u.getOriginalUploadId(),
                u.getAssignedToUserId(),
                u.getAssignedToName(),
                u.getApprovalStatus()
        );
    }

    /**
     * Returns all versions of a file (v1 first), identified by any version's upload ID.
     */
    public List<ImageUploadDto> getAllVersions(Long uploadId) {
        ImageUpload upload = imageUploadRepository.findById(uploadId).orElse(null);
        if (upload == null) return List.of();

        Long rootId = upload.getOriginalUploadId() != null ? upload.getOriginalUploadId() : uploadId;
        ImageUpload v1 = imageUploadRepository.findById(rootId).orElse(null);
        if (v1 == null) return List.of();

        List<ImageUpload> versions = new ArrayList<>();
        versions.add(v1);
        versions.addAll(imageUploadRepository.findByOriginalUploadIdOrderByVersionNumberAsc(rootId));

        return versions.stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * Creates a new version of an existing image from already-rendered bytes (used when a user
     * adds an annotated comment via {@code POST /api/assets/{id}/comments} — see
     * AssetService#addComment). The original asset/version is never overwritten; this always
     * inserts a brand-new {@link ImageUpload} row in the same version chain, following the
     * exact same numbering scheme as a normal re-upload (see {@link #resolveVersion}).
     * AI tagging/QC are intentionally skipped — this is a user-authored markup version, not a
     * fresh raw upload — but metadata (dimensions, preview) is still extracted for consistency.
     */
    public ImageUploadDto createAnnotatedVersion(Long sourceUploadId, byte[] imageBytes, String contentType, String uploaderEmail) {
        ImageUpload source = imageUploadRepository.findById(sourceUploadId).orElse(null);
        if (source == null) return null;

        User uploader = uploaderEmail != null ? userRepository.findByEmail(uploaderEmail).orElse(null) : null;
        String originalFilename = source.getFileName();
        Long batchId = source.getBatch() != null ? source.getBatch().getId() : null;

        String fileName = UUID.randomUUID() + "_" + originalFilename;
        String gcsPath = uploadToGcs(fileName, imageBytes, contentType);
        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + fileName;

        long[] ver;
        ImageUpload imageUpload;
        synchronized (versionLockFor(originalFilename, batchId)) {
            ver = resolveVersion(originalFilename, batchId);

            imageUpload = ImageUpload.builder()
                    .fileName(originalFilename)
                    .gcsPath(gcsPath)
                    .publicUrl(publicUrl)
                    .contentType(contentType)
                    .fileSize((long) imageBytes.length)
                    .project(source.getProject())
                    .uploadedBy(uploader)
                    .createdAt(LocalDateTime.now())
                    .versionNumber((int) ver[0])
                    .originalUploadId(ver[1] < 0 ? null : ver[1])
                    .uploadStatus("COMPLETED")
                    .build();
            if (source.getBatch() != null) {
                imageUpload.setBatch(source.getBatch());
            }
            imageUpload = imageUploadRepository.save(imageUpload);
        }

        Long projectId = source.getProject() != null ? source.getProject().getId() : null;
        Long userId = uploader != null ? uploader.getId() : null;
        auditService.log("IMAGE_VERSION_FROM_ANNOTATION", projectId, batchId, imageUpload.getId(), userId,
                "Created annotated version v" + ver[0] + " of " + originalFilename);

        extractAndSaveMetadata(imageUpload, imageBytes);

        return toDto(imageUpload);
    }

    /**
     * Returns [versionNumber, originalUploadId] for a new upload.
     * -1 in position 1 means originalUploadId should be null (i.e. this is the first version).
     */
    private long[] resolveVersion(String fileName, Long batchId) {
        if (batchId == null) return new long[]{1L, -1L};
        List<ImageUpload> existing = imageUploadRepository
                .findByFileNameAndBatchIdOrderByVersionNumberDesc(fileName, batchId);
        if (existing.isEmpty()) {
            // Fall back to matching by base filename (extension stripped) within the batch, so
            // re-uploading the same shot saved under a different file type (e.g. "shot.tif" then
            // "shot.jpg") still chains as a new version instead of starting an unlinked v1.
            String baseName = stripExtension(fileName);
            existing = imageUploadRepository.findByBatchIdOrderByCreatedAtDesc(batchId).stream()
                    .filter(u -> stripExtension(u.getFileName()).equalsIgnoreCase(baseName))
                    .sorted((a, b) -> Long.compare(
                            b.getVersionNumber() != null ? b.getVersionNumber() : 1,
                            a.getVersionNumber() != null ? a.getVersionNumber() : 1))
                    .toList();
        }
        if (existing.isEmpty()) return new long[]{1L, -1L};
        ImageUpload latest = existing.get(0);
        long nextVer = (latest.getVersionNumber() != null ? latest.getVersionNumber() : 1) + 1L;
        long origId = latest.getOriginalUploadId() != null ? latest.getOriginalUploadId() : latest.getId();
        return new long[]{nextVer, origId};
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    // Per (batchId, fileName) locks so concurrent uploads of same-named files within a batch
    // (e.g. the 6-thread imageUploadExecutor processing a multi-file batch upload in parallel)
    // resolve versions sequentially instead of racing: without this, two files with the same
    // name uploaded in the same request could both see "no existing version" and both get
    // saved as v1 with no originalUploadId, leaving them unlinked instead of stacked.
    private final java.util.concurrent.ConcurrentHashMap<String, Object> versionResolutionLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object versionLockFor(String fileName, Long batchId) {
        return versionResolutionLocks.computeIfAbsent(batchId + "::" + fileName, k -> new Object());
    }
}
