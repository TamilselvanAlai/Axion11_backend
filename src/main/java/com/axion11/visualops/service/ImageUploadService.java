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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
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
    private final ExecutorService imageUploadExecutor;
    private final BatchEventService batchEventService;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String geminiModel;

    /** Forces ImageIO to (re-)scan for plugins under this class's own classloader. Spring Boot's
     *  executable jar keeps dependency jars nested (BOOT-INF/lib/*.jar) and loads them through
     *  its own classloader — ImageIO's SPI registry is a JVM-wide singleton that by default
     *  populates itself from whichever classloader happens to be the thread context classloader
     *  the first time it's touched, which isn't guaranteed to see nested-jar service providers.
     *  Without this, TwelveMonkeys' PSD/TIFF ImageIO plugins (declared as regular Maven
     *  dependencies) can silently fail to register, so getImageReaders() finds nothing for a
     *  .psd/.tif file and preview generation does nothing — no exception, no log, just an
     *  asset with no thumbnail. */
    @jakarta.annotation.PostConstruct
    void registerImageIOPlugins() {
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        ImageIO.scanForPlugins();
        log.info("ImageIO plugins after scan: {}", String.join(", ", ImageIO.getReaderFormatNames()));
    }

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

    /** Moves a set of uploads into a different batch — the upload's project follows the new batch's.
     *  Keeps each source/target batch's totalImages counter in sync with the move so it doesn't
     *  drift out from under the folder list's asset count (see BatchService#toBatchDtoMinimal,
     *  which now live-counts rather than trusting this counter, but other logic — e.g.
     *  BatchRepository#completeIfAllUploaded — still depends on it being accurate). */
    @Transactional
    public List<ImageUploadDto> moveUploadsToBatch(List<Long> uploadIds, Long batchId) {
        Batch targetBatch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + batchId));
        List<ImageUpload> uploads = imageUploadRepository.findAllById(uploadIds);

        Map<Long, Long> movedOutBySourceBatch = uploads.stream()
                .filter(u -> u.getBatch() != null && !u.getBatch().getId().equals(batchId))
                .collect(Collectors.groupingBy(u -> u.getBatch().getId(), Collectors.counting()));

        for (ImageUpload upload : uploads) {
            upload.setBatch(targetBatch);
            upload.setProject(targetBatch.getProject());
        }
        imageUploadRepository.saveAll(uploads);

        movedOutBySourceBatch.forEach((sourceBatchId, count) ->
                batchRepository.decrementTotalImages(sourceBatchId, count.intValue()));
        int totalMovedIn = movedOutBySourceBatch.values().stream().mapToInt(Long::intValue).sum();
        if (totalMovedIn > 0) {
            batchRepository.incrementTotalImages(batchId, totalMovedIn);
        }

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

    /**
     * Regenerates JPEG previews for existing PSD/RAW/video uploads that don't have one yet —
     * either because they were uploaded before preview generation existed, or because the
     * original generation attempt failed. Re-downloads each file and re-runs the same
     * extraction pipeline used on upload.
     *
     * Capped at {@code limit} candidates per call — decoding a large PSD/TIFF into a
     * BufferedImage can use hundreds of MB of heap, and running the whole backlog in one
     * request risked an OutOfMemoryError. Callers should keep invoking this until it returns
     * fewer than {@code limit} updates (or a 0-candidate response) to drain the full backlog.
     */
    public int backfillPreviews(int limit) {
        List<ImageUpload> candidates = imageUploadRepository.findByPreviewUrlIsNull().stream()
                .filter(u -> needsPreview(u.getFileName()) || isVideoPreview(u.getFileName()))
                .limit(Math.max(1, limit))
                .toList();

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        int updated = 0;
        for (ImageUpload upload : candidates) {
            try {
                byte[] bytes = downloadImage(httpClient, upload.getPublicUrl());
                if (bytes == null) {
                    log.warn("Backfill: could not download {} ({})", upload.getFileName(), upload.getId());
                    continue;
                }
                extractAndSaveMetadata(upload, bytes);
                if (upload.getPreviewUrl() != null) {
                    updated++;
                }
            } catch (Exception e) {
                log.error("Backfill failed for upload {} ({}): {}", upload.getId(), upload.getFileName(), e.getMessage(), e);
            }
        }
        log.info("Preview backfill complete: {}/{} candidates got a new preview", updated, candidates.size());
        return updated;
    }

    /**
     * Force-regenerates JPEG previews for existing RAW camera uploads (CR3/CR2/NEF/ARW/DNG/RAW),
     * even ones that already have a preview — unlike {@link #backfillPreviews}, which only fills
     * in previews that are missing entirely. Exists because the RAW preview pipeline's own logic
     * has changed since some of these were first uploaded (most recently: correcting for
     * orientation via ExifTool — see {@link #resolveOrientation}), so a file that already has a
     * preview can still have the *wrong* one sitting on disk. Re-downloads each file's original
     * bytes and re-runs the same extraction used on upload, overwriting whatever preview is
     * there now.
     *
     * Capped at {@code limit} per call for the same reason as backfillPreviews — callers should
     * keep invoking this until it returns fewer than {@code limit} updates to drain the backlog.
     */
    public int regenerateRawPreviews(int limit) {
        List<ImageUpload> candidates = imageUploadRepository.findRawCameraUploads().stream()
                .limit(Math.max(1, limit))
                .toList();

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        int updated = 0;
        for (ImageUpload upload : candidates) {
            try {
                byte[] bytes = downloadImage(httpClient, upload.getPublicUrl());
                if (bytes == null) {
                    log.warn("Preview regen: could not download {} ({})", upload.getFileName(), upload.getId());
                    continue;
                }
                // extractAndSaveMetadata only generates a preview when previewUrl is currently
                // null (see its RAW fallback branch) — clear it first so an existing (possibly
                // wrong) preview doesn't make it skip regeneration.
                upload.setPreviewUrl(null);
                extractAndSaveMetadata(upload, bytes);
                if (upload.getPreviewUrl() != null) {
                    updated++;
                }
            } catch (Exception e) {
                log.error("Preview regen failed for upload {} ({}): {}", upload.getId(), upload.getFileName(), e.getMessage(), e);
            }
        }
        log.info("RAW preview regeneration complete: {}/{} candidates got a fresh preview", updated, candidates.size());
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
     * Generates a signed URL for direct browser-to-GCS upload — the only path that isn't capped
     * by Cloud Run's ~32MB request-body limit, so every file (any size) must go through this,
     * never the direct-multipart fallback, in production.
     *
     * Cloud Run's attached service account has no downloadable private key, and
     * {@code Storage#signUrl} needs one to sign locally — on a bare {@link ComputeEngineCredentials}
     * it throws ("Signing key not available"). The fix is to impersonate that *same* service
     * account via the IAM Credentials API (signBlob), which works without a key file. This
     * requires the runtime service account to hold roles/iam.serviceAccountTokenCreator on
     * itself; see deploy_backend_prod.ps1 / README for the one-time
     * `gcloud iam service-accounts add-iam-policy-binding` setup.
     */
    public Map<String, String> generateSignedUploadUrl(String originalFileName, String contentType) {
        // Validated outside the try/catch below on purpose: that catch maps every failure
        // (including a real signing error) to {"supported": "false"}, which the frontend reads
        // as "fall back to the multipart endpoint" — an unsupported file type must instead
        // surface as an explicit 400 (via IllegalArgumentException -> GlobalExceptionHandler),
        // not get silently retried on a different upload path.
        validateUploadExtension(originalFileName);
        try {
            com.google.auth.oauth2.GoogleCredentials sourceCredentials =
                    com.google.auth.oauth2.GoogleCredentials.getApplicationDefault();
            Storage storage;
            if (sourceCredentials instanceof com.google.auth.oauth2.ComputeEngineCredentials) {
                String serviceAccountEmail =
                        ((com.google.auth.oauth2.ComputeEngineCredentials) sourceCredentials).getAccount();
                com.google.auth.oauth2.ImpersonatedCredentials signingCredentials =
                        com.google.auth.oauth2.ImpersonatedCredentials.create(
                                sourceCredentials,
                                serviceAccountEmail,
                                null,
                                Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"),
                                300);
                storage = StorageOptions.newBuilder().setCredentials(signingCredentials).build().getService();
            } else {
                // Local dev with a downloaded service-account key file (has its own private
                // key) — can sign directly, no impersonation needed.
                storage = StorageOptions.getDefaultInstance().getService();
            }

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
            log.warn("Failed to generate GCS signed URL (likely local dev without service account key, or " +
                    "missing roles/iam.serviceAccountTokenCreator self-binding in production): {}", e.getMessage());
            return Map.of(
                    "supported", "false",
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
            );
        }
    }

    /**
     * Saves an editor's local edit-and-resync as the chain's "VE" (established) version — never
     * v1, and never any other already-finalized version, both of which stay untouched as
     * permanent history. The rule:
     *   - If the chain already has an established version, this save replaces its content in
     *     place (same row, same version number) — a second/third/... edit doesn't fork more rows.
     *   - Otherwise (no current draft — either this is the first-ever edit, or a previous draft
     *     was already finalized by approval/re-upload), a new row is created, one slot past the
     *     chain's current highest version number, and marked established.
     * Either way the result is draft — the version number only actually advances when QC
     * approves it (AssetService#approveAsset already advances-in-place on approval).
     *
     * @param anyIdInChain any version's id in the same chain as the edited asset (typically
     *                     whichever version was open locally when the edit was saved)
     */
    // READ_COMMITTED, not the default REPEATABLE READ: under REPEATABLE READ, this transaction's
    // plain (non-locking) reads keep using the snapshot from its very first query, even after
    // waiting on findByIdForUpdate's row lock below and even though the row that was being
    // waited on has since committed new sibling rows. That let two concurrent edits of the same
    // asset both "wait their turn" correctly but still both see "no established version yet"
    // afterward, each forking its own row. READ_COMMITTED re-reads latest-committed data on every
    // statement, so the second transaction sees the first one's committed row once it's unblocked.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImageUploadDto syncEditedVersion(Long anyIdInChain, String gcsFileName, String contentType, long fileSize, String uploaderEmail) {
        ImageUpload anchor = imageUploadRepository.findById(anyIdInChain)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + anyIdInChain));

        // DB-level lock on the chain's root row, held for the rest of this transaction — see
        // ImageUploadRepository#findByIdForUpdate. The `synchronized` block below is kept too
        // (harmless, and still helps within a single instance) but can't be relied on alone: two
        // near-simultaneous saves for the same asset can land on different Cloud Run instances,
        // where an in-process lock provides no protection and both could see "no established
        // version yet" and each create their own.
        Long chainRootId = anchor.getOriginalUploadId() != null ? anchor.getOriginalUploadId() : anchor.getId();
        long lockWaitStart = System.currentTimeMillis();
        imageUploadRepository.findByIdForUpdate(chainRootId);
        log.info("syncEditedVersion[{}]: lock on chainRoot={} acquired after {}ms (thread={})",
                anyIdInChain, chainRootId, System.currentTimeMillis() - lockWaitStart, Thread.currentThread().getName());

        String gcsPath = "gs://" + bucketName + "/" + gcsFileName;
        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + gcsFileName;

        synchronized (versionLockFor(anchor.getFileName(), anchor.getBatch() != null ? anchor.getBatch().getId() : null)) {
            List<ImageUpload> chain = resolveVersionChain(anchor.getId());
            ImageUpload established = chain.stream().filter(ImageUpload::isEstablished).findFirst().orElse(null);
            log.info("syncEditedVersion[{}]: chainRoot={} resolved {} row(s) [{}], established={}",
                    anyIdInChain, chainRootId, chain.size(),
                    chain.stream().map(u -> u.getId() + "/v" + u.getVersionNumber() + (u.isEstablished() ? "*" : "")).toList(),
                    established != null ? established.getId() : "none");

            ImageUpload target;
            if (established != null) {
                // Second+ edit — update the existing VE row in place. v1 (and every other
                // version in the chain) is never touched.
                target = established;
            } else {
                // No current draft — every existing row (v1 and any already-finalized versions)
                // is permanent history and stays untouched; the edit becomes a brand-new row.
                ImageUpload v1 = chain.isEmpty() ? anchor : chain.get(0);
                Long rootId = v1.getOriginalUploadId() != null ? v1.getOriginalUploadId() : v1.getId();
                int nextVersion = chain.stream()
                        .mapToInt(u -> u.getVersionNumber() != null ? u.getVersionNumber() : 1)
                        .max().orElse(1) + 1;

                User uploader = uploaderEmail != null ? userRepository.findByEmail(uploaderEmail).orElse(null) : null;
                target = ImageUpload.builder()
                        .fileName(v1.getFileName())
                        .gcsPath(gcsPath)
                        .publicUrl(publicUrl)
                        .contentType(contentType)
                        .fileSize(fileSize)
                        .project(v1.getProject())
                        .batch(v1.getBatch())
                        .uploadedBy(uploader)
                        .createdAt(LocalDateTime.now())
                        .versionNumber(nextVersion)
                        .originalUploadId(rootId)
                        .uploadStatus("COMPLETED")
                        .build();
            }

            target.setGcsPath(gcsPath);
            target.setPublicUrl(publicUrl);
            // Cleared up front in case preview regeneration below fails — better to fall back to
            // the fresh publicUrl than keep showing a stale thumbnail of the pre-edit content.
            target.setPreviewUrl(null);
            // An editor save-as can change the format (e.g. JPG -> TIFF) without touching the
            // file name otherwise — keep the extension truthful, since needsPreview() below (and
            // the desktop app's own file-type fallback) both read it off the name.
            target.setFileName(renameExtensionForContentType(target.getFileName(), contentType));
            target.setContentType(contentType);
            target.setFileSize(fileSize);
            target.setApprovalStatus("draft");
            // The old automated QC result (if any) was for different content — clear it so
            // status resolution (which falls back to this when approvalStatus isn't an explicit
            // approved/rejected/live) can't read a stale "PASSED" and show this fresh,
            // unreviewed edit as already approved.
            target.setImageQualityQcCheck(null);
            target = imageUploadRepository.save(target);
            log.info("syncEditedVersion[{}]: target row id={} v{} (thread={})",
                    anyIdInChain, target.getId(), target.getVersionNumber(), Thread.currentThread().getName());

            markEstablished(target.getId());

            // Regenerate dimensions/preview from the actual new content — without this, formats
            // the browser can't render directly (PSD, RAW, etc.) would have no thumbnail at all
            // now that the stale one was cleared above. Downloading the bytes back from GCS here
            // (server-to-server) rather than accepting them in this request is what keeps this
            // endpoint's own request small — the desktop app already learned the hard way that a
            // large multipart body here would hit Cloud Run's request-size limit.
            if (contentType != null && contentType.startsWith("image/")) {
                try {
                    byte[] bytes = downloadFromGcs(gcsFileName);
                    if (bytes != null) {
                        extractAndSaveMetadata(target, bytes);
                    }
                } catch (Exception e) {
                    log.warn("Preview regeneration failed for upload {}: {}", target.getId(), e.getMessage());
                }
            }

            return getUpload(target.getId());
        }
    }

    /**
     * Confirms a file uploaded directly to GCS. Creates DB record and triggers async AI processing.
     */
    public ImageUploadDto confirmDirectUpload(String gcsFileName, String originalFileName, String contentType,
                                               long fileSize, Long projectId, Long batchId, String uploaderEmail) {
        // Defense in depth: generateSignedUploadUrl already rejected an unsupported extension
        // before handing out a write URL, but this call is a separate, independently-reachable
        // endpoint — re-check here too rather than trusting the earlier step was actually used.
        validateUploadExtension(originalFileName);
        User uploader = userRepository.findByEmail(uploaderEmail).orElse(null);
        Project project = projectId != null ? projectRepository.findById(projectId).orElse(null) : null;

        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + gcsFileName;
        String gcsPath = "gs://" + bucketName + "/" + gcsFileName;

        ImageUpload imageUpload;
        synchronized (versionLockFor(originalFileName, batchId)) {
            // A plain re-upload of a matching filename always becomes a brand-new version — it
            // must never overwrite an existing row, established (VE) or otherwise, both of which
            // are permanent history (see syncEditedVersion, which is the only path allowed to
            // mutate a version's content in place, and only the current VE at that).
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
                    // The frontend only sends a batchId when uploading directly into a batch (no
                    // projectId) — without this, the row's own project_id stayed NULL forever.
                    // That's invisible to anything that queries by batch_id (e.g. the desktop
                    // app's asset list), but the web app's project-tree endpoint queries uploads
                    // by their own project_id and silently excludes rows with a NULL one, showing
                    // "Total Assets: 0" for a batch that visibly has assets elsewhere.
                    if (imageUpload.getProject() == null) {
                        imageUpload.setProject(batch.getProject());
                    }
                }
            }

            imageUpload = imageUploadRepository.save(imageUpload);
        }

        // Increment batch upload counter
        if (batchId != null) {
            batchRepository.incrementUploadedImages(batchId);
            // Push the SSE status event the desktop/web app's upload UI actually waits on (see
            // BatchService#uploadImagesAsync for the multipart-upload path, which already does
            // this) — without it, completeIfAllUploaded still flips the row in the DB, but no
            // connected client is ever told, so the "Processing…" indicator never clears until
            // its own client-side timeout.
            if (batchRepository.completeIfAllUploaded(batchId) > 0) {
                batchEventService.publishStatus(batchId, "COMPLETED");
            }
        }

        Long pid = project != null ? project.getId() : null;
        Long bid = batchId;
        Long uid = uploader != null ? uploader.getId() : null;
        auditService.log("IMAGE_UPLOAD", pid, bid, imageUpload.getId(), uid, "Uploaded " + originalFileName);

        // Download from GCS + metadata extraction + AI tagging happen off the request thread:
        // for a large TIFF/PSD/RAW master this tail can easily take longer than the frontend's
        // request timeout, which used to surface as a false "upload failed" even though the GCS
        // PUT and the DB row above already succeeded. The response below returns as soon as the
        // row exists, regardless of file size; the preview/thumbnail appears a little later.
        final Long uploadId = imageUpload.getId();
        final String ct = contentType != null ? contentType : "";
        imageUploadExecutor.execute(() -> processConfirmedUpload(uploadId, gcsFileName, originalFileName, fileSize, ct));

        return toDto(imageUpload);
    }

    /**
     * Runs off the request thread (see confirmDirectUpload): downloads the confirmed upload's
     * bytes from GCS, extracts metadata and generates its preview/thumbnail, and — for images
     * small enough for the Vision API — runs AI tagging.
     */
    private void processConfirmedUpload(Long uploadId, String gcsFileName, String originalFileName, long fileSize, String ct) {
        final long maxDownloadSize = 40L * 1024 * 1024; // 40MB Vision API limit

        // Metadata/preview extraction runs regardless of contentType: browsers routinely send
        // an empty or generic "application/octet-stream" type for formats they don't have a
        // registered MIME for (CR3, HEIC, and other RAW/camera formats all hit this in
        // practice), and gating on ct.startsWith("image/") here used to silently skip preview
        // generation entirely for exactly those files. Vision API tagging genuinely needs real
        // decodable image bytes, so that alone stays gated below.
        try {
            ImageUpload u = imageUploadRepository.findById(uploadId).orElse(null);
            if (u != null) {
                if (fileSize <= maxDownloadSize) {
                    byte[] bytes = downloadFromGcs(gcsFileName);
                    if (bytes != null) {
                        extractAndSaveMetadata(u, bytes);
                        if (ct.startsWith("image/")) {
                            analyzeImageDeferred(uploadId, bytes);
                        }
                    }
                } else {
                    log.info("File {} is {}MB, too large for Vision API. Generating preview.", originalFileName, fileSize / (1024 * 1024));
                    try {
                        byte[] fullBytes = downloadFromGcs(gcsFileName);
                        if (fullBytes != null) {
                            extractAndSaveMetadata(u, fullBytes);
                            fullBytes = null;
                        }
                    } catch (Throwable metaEx) {
                        // Throwable, not Exception: decoding a large TIFF/PSD/RAW master can throw
                        // OutOfMemoryError deep inside ImageIO, which a plain `catch (Exception)`
                        // lets straight through — silently killing this background task before any
                        // preview is saved. Catching it here just logs it and leaves the row as-is
                        // (still previewless), instead of leaving no trace at all.
                        log.error("Metadata extraction failed for large file {}: {}", originalFileName, metaEx.getMessage(), metaEx);
                    }
                    if (ct.startsWith("image/")) {
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
            }
        } catch (Throwable e) {
            // Throwable, not Exception, for the same reason as the inner catch above: this whole
            // method runs on a background executor thread, and an uncaught Error (e.g. an
            // OutOfMemoryError from decoding a huge master file) kills that thread silently
            // instead of just failing this one upload's metadata/preview extraction.
            log.error("Processing failed for confirmed upload {}: {}", uploadId, e.getMessage(), e);
        }
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
        validateUploadExtension(originalFilename);
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
            // A plain re-upload of a matching filename always becomes a brand-new version — see
            // the identical comment in confirmDirectUpload for why this never overwrites an
            // existing row (established/VE or otherwise).
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

        // This streaming path intentionally never holds the whole file in memory for the GCS
        // upload above, which meant metadata/thumbnail extraction was silently skipped for every
        // file large enough to land here (TIFF/PSD masters routinely are) — unlike the small-file
        // path (uploadToGcsAndSave) and the edit-resync path (syncEditedVersion), both of which
        // already do this. Downloading the bytes back from GCS here, same as those two, is what
        // finally generates the thumbnail/preview on initial upload instead of only after an edit.
        try {
            byte[] freshBytes = downloadFromGcs(fileName);
            if (freshBytes != null) {
                extractAndSaveMetadata(imageUpload, freshBytes);
            }
        } catch (Exception e) {
            log.warn("Metadata/preview extraction failed for {}: {}", originalFilename, e.getMessage());
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
        validateUploadExtension(originalFilename);
        String fileName = UUID.randomUUID() + "_" + originalFilename;

        String gcsPath = uploadToGcs(fileName, bytes, contentType);
        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + fileName;

        ImageUpload imageUpload;
        synchronized (versionLockFor(originalFilename, batchId)) {
            // A plain re-upload of a matching filename always becomes a brand-new version — see
            // the identical comment in confirmDirectUpload for why this never overwrites an
            // existing row (established/VE or otherwise).
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
        validateUploadExtension(originalFilename);
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
        if (isVideoPreview(imageUpload.getFileName())) {
            try {
                String previewUrl = extractVideoFramePreview(bytes, imageUpload);
                if (previewUrl != null) {
                    imageUpload.setPreviewUrl(previewUrl);
                    log.info("Generated video frame preview for {}: {}", imageUpload.getFileName(), previewUrl);
                }
            } catch (Throwable e) {
                log.warn("Failed to extract video frame preview for {}: {}", imageUpload.getFileName(), e.getMessage());
            }
            imageUploadRepository.save(imageUpload);
            return;
        }

        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(bais);
            if (iis != null) {
                java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) {
                    log.warn("No ImageIO reader found for {} — format plugin (e.g. TwelveMonkeys PSD/TIFF) may not be registered", imageUpload.getFileName());
                }
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
                    } catch (Throwable ignore) {
                        // DPI is a nice-to-have, not essential — but TwelveMonkeys' TIFF metadata
                        // tree-building (asTree) can throw OutOfMemoryError on some TIFFs with
                        // large metadata blocks, which `catch (Exception)` alone does NOT catch
                        // (Error is a sibling of Exception, not a subtype). Left uncaught, that
                        // OOM used to kill this whole background thread and abort the width/
                        // height/preview extraction below it too — silently leaving the asset
                        // with no thumbnail at all. Catching Throwable here keeps a DPI failure
                        // exactly as ignorable as it always was, without taking the rest of
                        // metadata/preview extraction down with it.
                    }
                    try {
                        java.awt.image.BufferedImage img;
                        try {
                            // The preview this feeds into is capped at 2000px on its longest
                            // side (see generateAndUploadPreview) — decoding a source many
                            // times larger than that at full resolution just to immediately
                            // downscale it wastes huge amounts of heap (a several-hundred-MB
                            // to multi-GB TIFF/PSD master can need many times its file size as
                            // a decoded BufferedImage) and risks an OutOfMemoryError that would
                            // abort the whole request before any preview is saved. Subsample
                            // down towards preview resolution instead whenever the reader
                            // supports it.
                            int fullWidth = reader.getWidth(0);
                            int fullHeight = reader.getHeight(0);
                            int longestSide = Math.max(fullWidth, fullHeight);
                            int previewMaxDim = 2000;
                            javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                            int subsampling = longestSide > previewMaxDim ? Math.max(1, longestSide / previewMaxDim) : 1;
                            if (subsampling > 1) {
                                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                            }
                            try {
                                img = reader.read(0, param);
                            } catch (Throwable subsampleEx) {
                                // Throwable, not Exception: a reader that rejects a subsampling
                                // stride can also throw OutOfMemoryError (not just a decode
                                // Exception) if it buffers more than expected before honoring the
                                // stride — catching only Exception let that OOM skip the whole
                                // retry ladder below and kill the caller's background thread.
                                // Before giving up, retry with progressively coarser strides
                                // (smaller output, less memory), since a reader that rejects one
                                // factor often accepts a larger one. Only fall back to a full
                                // decode (small images only) or skip the preview entirely if
                                // every stride fails, to avoid exhausting heap.
                                java.awt.image.BufferedImage retried = null;
                                for (int coarser = subsampling * 2; coarser <= 32 && retried == null; coarser *= 2) {
                                    try {
                                        javax.imageio.ImageReadParam retryParam = reader.getDefaultReadParam();
                                        retryParam.setSourceSubsampling(coarser, coarser, 0, 0);
                                        retried = reader.read(0, retryParam);
                                    } catch (Throwable ignoredRetry) {
                                        // try the next, coarser stride
                                    }
                                }
                                if (retried != null) {
                                    img = retried;
                                } else if ((long) fullWidth * fullHeight <= 4000L * 4000L) {
                                    img = reader.read(0);
                                } else {
                                    log.warn("Skipping preview for {} — {}x{} is too large to safely decode and its reader doesn't support subsampled reads",
                                            imageUpload.getFileName(), fullWidth, fullHeight);
                                    img = null;
                                }
                            }
                        } catch (Throwable readEx) {
                            // Throwable: decoding a large/uncompressed-in-memory TIFF or PSD is
                            // exactly the kind of operation that can throw OutOfMemoryError rather
                            // than a plain decode Exception.
                            log.warn("ImageIO could not decode {} (reader {}): {}",
                                    imageUpload.getFileName(), reader.getClass().getSimpleName(), readEx.getMessage());
                            img = null;
                        }
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
                                } catch (Throwable previewEx) {
                                    log.warn("Failed to generate preview for {}: {}", imageUpload.getFileName(), previewEx.getMessage());
                                }
                            }
                        }
                    } catch (Throwable e) {
                        log.warn("Preview/color-space step failed for {} (reader {}): {}",
                                imageUpload.getFileName(), reader.getClass().getSimpleName(), String.valueOf(e), e);
                    }
                    reader.dispose();
                }
                iis.close();
            }
        } catch (Throwable e) {
            // Throwable throughout this method, not just Exception: an OutOfMemoryError while
            // decoding a large TIFF/PSD source is a real, observed failure mode here (see the
            // narrower catches above) — anything weaker than Throwable at any of these levels
            // lets that Error skip straight past this whole method and kill whatever background
            // thread called it (processConfirmedUpload/syncEditedVersion/etc.), silently leaving
            // the asset with no width/height/preview at all instead of just logging and moving on.
            log.warn("ImageIO reader setup failed for {}: {}", imageUpload.getFileName(), String.valueOf(e), e);
        }

        // Fallback: for RAW files (CR3, CR2, NEF, ARW) that ImageIO can't read,
        // extract the embedded JPEG preview using metadata-extractor
        if (imageUpload.getPreviewUrl() == null && needsPreview(imageUpload.getFileName())) {
            try {
                String previewUrl = extractEmbeddedPreview(bytes, imageUpload);
                if (previewUrl != null) {
                    imageUpload.setPreviewUrl(previewUrl);
                    log.info("Extracted embedded preview for {}: {}", imageUpload.getFileName(), previewUrl);
                }
            } catch (Throwable e) {
                log.warn("Failed to extract embedded preview for {}: {}", imageUpload.getFileName(), e.getMessage());
            }
        }

        imageUploadRepository.save(imageUpload);
    }

    // ── Upload validation ────────────────────────────────────────────────────

    /** Every extension the app recognizes as an uploadable asset — kept in sync with the
     *  frontend's file-picker `accept` list and FTP-browse filter (UploadAssets.tsx) so a file a
     *  user can select in the UI is never then rejected server-side, and vice versa. This is the
     *  one real enforcement point: client-side `accept` attributes are just UI hints (bypassable
     *  via drag-and-drop or an OS "All Files" picker), so every upload entry point below calls
     *  {@link #validateUploadExtension} before any bytes reach GCS or a DB row is created. */
    private static final Set<String> ALLOWED_UPLOAD_EXTENSIONS = Set.of(
            // images
            "jpg", "jpeg", "png", "webp", "tif", "tiff", "gif", "bmp", "svg",
            // design/prepress
            "eps", "ai", "psd",
            // RAW — same set as NEEDS_PREVIEW_EXTS's RAW extensions
            "raw", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng", "raf",
            "rw2", "rwl", "orf", "pef", "ptx", "srw", "x3f", "3fr", "fff", "iiq", "mef",
            "mos", "erf", "kdc", "dcr", "mrw", "gpr",
            // modern image containers
            "heic", "heif", "avif",
            // video
            "mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v", "mpg", "mpeg",
            // documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv",
            // 3D
            "obj", "fbx", "gltf", "glb", "stl", "3ds", "dae", "blend", "usdz",
            // archives
            "zip"
    );

    /** Rejects an upload whose extension isn't in {@link #ALLOWED_UPLOAD_EXTENSIONS} — thrown as
     *  {@link IllegalArgumentException} so {@code GlobalExceptionHandler} turns it into a 400
     *  with this message, rather than a file type silently reaching GCS or the DB. */
    private void validateUploadExtension(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("File name is required");
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        if (ext.isEmpty() || !ALLOWED_UPLOAD_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: ." + ext + " (" + fileName + ")");
        }
    }

    // ── Preview generation ─────────────────────────────────────────────────────

    /** Content types recognized well enough to rewrite a file name's extension for. */
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.ofEntries(
            Map.entry("image/tiff", "tiff"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/jpg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/vnd.adobe.photoshop", "psd"),
            Map.entry("application/x-photoshop", "psd"),
            Map.entry("image/x-canon-cr3", "cr3")
    );

    /**
     * Swaps a file name's extension to match its actual content type — used when an edit save
     * changes format (e.g. JPG -> TIFF) so the stored name doesn't keep lying about what the
     * bytes actually are. Unrecognized content types are left alone rather than guessed at.
     */
    private String renameExtensionForContentType(String fileName, String contentType) {
        if (fileName == null || contentType == null) return fileName;
        String newExt = CONTENT_TYPE_EXTENSIONS.get(contentType.toLowerCase());
        if (newExt == null) return fileName;

        int dot = fileName.lastIndexOf('.');
        String currentExt = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        boolean alreadyEquivalent = currentExt.equals(newExt)
                || (newExt.equals("jpg") && currentExt.equals("jpeg"))
                || (newExt.equals("tiff") && currentExt.equals("tif"));
        if (alreadyEquivalent) return fileName;

        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return base + "." + newExt;
    }

    /** Extensions that browsers cannot render natively — need a JPEG preview.
     *
     *  The RAW half of this set is intentionally the full known camera-RAW extension landscape
     *  (every current DSLR/mirrorless/medium-format manufacturer), not just the formats we've
     *  been asked to support so far. extractEmbeddedPreview() doesn't parse any vendor-specific
     *  RAW structure — it just pulls the JPEG every RAW file already embeds (via Exif preview
     *  tags, or a raw byte scan as fallback), so any format in this set works automatically with
     *  no other code change. That's also why we list extensions explicitly here instead of
     *  content-sniffing unrecognized uploads for an embedded JPEG: PDFs, DOCX/PPTX and other
     *  office formats routinely embed JPEGs internally too, and sniffing every upload would start
     *  generating bogus "previews" for those. A new camera RAW extension not yet in this list
     *  just needs adding here — everything downstream picks it up unchanged. */
    private static final Set<String> NEEDS_PREVIEW_EXTS = Set.of(
            "psd", "ai", "eps", "tif", "tiff", "heic", "heif",
            // Canon
            "cr2", "cr3", "crw",
            // Nikon
            "nef", "nrw",
            // Sony
            "arw", "srf", "sr2",
            // Adobe / generic DNG-based
            "dng",
            // Fujifilm
            "raf",
            // Generic/other TIFF-based RAW
            "raw",
            // Panasonic / Leica
            "rw2", "rwl",
            // Olympus
            "orf",
            // Pentax
            "pef", "ptx",
            // Samsung
            "srw",
            // Sigma
            "x3f",
            // Hasselblad / Imacon
            "3fr", "fff",
            // Phase One
            "iiq",
            // Mamiya
            "mef",
            // Leaf
            "mos",
            // Epson
            "erf",
            // Kodak
            "kdc", "dcr",
            // Minolta
            "mrw",
            // GoPro
            "gpr"
    );

    private boolean needsPreview(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return NEEDS_PREVIEW_EXTS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    /**
     * Video containers JCodec can demux (MP4-family/QuickTime). Other video extensions the
     * frontend recognizes (avi, webm, mkv, wmv, flv, mpg/mpeg) use codecs/containers JCodec
     * doesn't support and still fall back to the generic file-type icon.
     */
    private static final Set<String> VIDEO_PREVIEW_EXTS = Set.of("mp4", "mov", "m4v");

    private boolean isVideoPreview(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_PREVIEW_EXTS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    /**
     * Grabs a frame ~1s into the video (skips a possibly-black opening frame) via JCodec's
     * pure-Java MP4/QuickTime decoder, and uploads it as the JPEG preview/thumbnail.
     */
    private String extractVideoFramePreview(byte[] bytes, ImageUpload imageUpload) {
        java.io.File tempFile = null;
        try {
            tempFile = java.io.File.createTempFile("video_preview_", ".mp4");
            java.nio.file.Files.write(tempFile.toPath(), bytes);

            org.jcodec.common.model.Picture picture;
            try (org.jcodec.common.io.FileChannelWrapper ch = org.jcodec.common.io.NIOUtils.readableChannel(tempFile)) {
                org.jcodec.api.FrameGrab grab = org.jcodec.api.FrameGrab.createFrameGrab(ch);
                try {
                    grab.seekToSecondPrecise(1.0);
                } catch (Exception seekEx) {
                    // Clip shorter than 1s — fall through and grab whatever frame is current (the first one).
                }
                picture = grab.getNativeFrame();
            }
            if (picture == null) {
                log.warn("JCodec returned no frame for {}", imageUpload.getFileName());
                return null;
            }

            java.awt.image.BufferedImage frame = org.jcodec.scale.AWTUtil.toBufferedImage(picture);
            imageUpload.setWidth(frame.getWidth());
            imageUpload.setHeight(frame.getHeight());
            return generateAndUploadPreview(frame, imageUpload);
        } catch (Exception e) {
            log.warn("Video frame extraction failed for {}: {}", imageUpload.getFileName(), e.getMessage());
            return null;
        } finally {
            if (tempFile != null) tempFile.delete();
        }
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

            // Upload to GCS under previews/ prefix. Includes a timestamp, not just the upload's
            // id, so a VE re-save (which regenerates the preview at the *same* id every time —
            // see syncEditedVersion) gets a genuinely new URL instead of overwriting the same
            // GCS object path each time: browsers (and GCS's own edge caching) were caching the
            // first-ever preview indefinitely against that stable URL, so only the very first
            // save's thumbnail was ever visible no matter how many times it was resaved.
            String previewFileName = "previews/" + imageUpload.getId() + "_" + System.currentTimeMillis() + "_preview.jpg";
            uploadToGcs(previewFileName, jpegBytes, "image/jpeg");
            return "https://storage.googleapis.com/" + bucketName + "/" + previewFileName;
        } catch (Exception e) {
            log.warn("Preview generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts an embedded JPEG preview from formats ImageIO can't fully decode: RAW camera
     * files (CR3, CR2, NEF, ARW, DNG) via their Exif preview tags, and PSD/AI/etc. via their
     * embedded thumbnail resource. Two independent passes — the Exif-tag lookup and the
     * byte-level scan run as separate try blocks so a metadata-extractor parse failure on one
     * file type (e.g. an unusual PSD/CR3 structure) doesn't skip the more universal byte scan.
     */
    private String extractEmbeddedPreview(byte[] rawBytes, ImageUpload imageUpload) {
        // RAW camera files store their embedded JPEG preview in the sensor's native orientation
        // (often landscape, even for a portrait shot) and rely on separate metadata to say how a
        // viewer should rotate it — read once up front from the full file's metadata and apply to
        // whichever pass below finds the preview, so extracted CR3/CR2/etc. previews no longer
        // display sideways/upside down.
        int orientation = resolveOrientation(rawBytes);

        // Pass 1: Exif JPEGInterchangeFormat tags (0x0201/0x0202) — mainly RAW camera files.
        try {
            com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(
                    new java.io.ByteArrayInputStream(rawBytes));

            for (com.drew.metadata.Directory dir : metadata.getDirectories()) {
                if (dir.containsTag(0x0201) && dir.containsTag(0x0202)) {
                    int offset = dir.getInt(0x0201);
                    int length = dir.getInt(0x0202);
                    if (offset > 0 && length > 1000 && offset + length <= rawBytes.length) {
                        byte[] jpegBytes = java.util.Arrays.copyOfRange(rawBytes, offset, offset + length);
                        if (jpegBytes.length > 2 && (jpegBytes[0] & 0xFF) == 0xFF && (jpegBytes[1] & 0xFF) == 0xD8
                                && isDecodableJpeg(jpegBytes)) {
                            String previewUrl = uploadPreviewJpeg(applyExifOrientation(jpegBytes, orientation), imageUpload);
                            log.info("Extracted Exif-tagged preview for {}", imageUpload.getFileName());
                            return previewUrl;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("metadata-extractor pass failed for {} ({}) — falling back to byte scan",
                    imageUpload.getFileName(), e.getMessage());
        }

        // Pass 2: brute-force scan for embedded JPEG segments (FFD8...FFD9), largest first. Works
        // for PSD/AI thumbnail resources and any RAW variant regardless of container structure.
        // Candidates are validated by actually decoding them (see isDecodableJpeg) rather than
        // trusting the largest byte-8/FFD9 span found — RAW sensor data is high-entropy enough
        // that it can coincidentally contain a byte pair that merely *looks* like JPEG start/end
        // markers without being a real image, which used to get uploaded as the preview anyway
        // (uploadPreviewJpeg swallows its own ImageIO.read failure — it's only used there to
        // grab width/height, not to gate the upload), producing a previewUrl that no viewer,
        // including this app's own thumbnail renderer, could ever actually display.
        try {
            List<int[]> candidates = new ArrayList<>(); // [offset, length]
            for (int i = 0; i < rawBytes.length - 3; i++) {
                if ((rawBytes[i] & 0xFF) == 0xFF && (rawBytes[i + 1] & 0xFF) == 0xD8) {
                    for (int j = i + 2; j < rawBytes.length - 1; j++) {
                        if ((rawBytes[j] & 0xFF) == 0xFF && (rawBytes[j + 1] & 0xFF) == 0xD9) {
                            int len = j - i + 2;
                            if (len > 10000) { // Only consider JPEGs > 10KB
                                candidates.add(new int[]{i, len});
                            }
                            break;
                        }
                    }
                }
            }
            candidates.sort((a, b) -> b[1] - a[1]);

            for (int[] candidate : candidates) {
                byte[] jpegBytes = java.util.Arrays.copyOfRange(rawBytes, candidate[0], candidate[0] + candidate[1]);
                if (!isDecodableJpeg(jpegBytes)) continue;
                String previewUrl = uploadPreviewJpeg(applyExifOrientation(jpegBytes, orientation), imageUpload);
                log.info("Extracted JPEG preview from byte scan for {}: {} bytes at offset {}",
                        imageUpload.getFileName(), candidate[1], candidate[0]);
                return previewUrl;
            }
        } catch (Exception e) {
            log.warn("Byte-scan preview extraction failed for {}: {}", imageUpload.getFileName(), e.getMessage());
            return null;
        }

        log.warn("No embedded JPEG preview found in {}", imageUpload.getFileName());
        return null;
    }

    /** Whether {@code bytes} actually decodes as a real image, not just something that starts
     *  with FFD8 and ends with FFD9 — see the Pass 2 comment above for why that byte-level check
     *  alone isn't trustworthy for a preview extracted out of raw sensor data. */
    private boolean isDecodableJpeg(byte[] bytes) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            return img != null && img.getWidth() > 0 && img.getHeight() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Orientation (1-8, standard TIFF/Exif meaning) resolved via whichever method actually
     *  works for this file. Tries ExifTool first — a mature external tool that reliably reads
     *  RAW camera metadata (CR3/CR2/NEF/ARW/etc.) across the wide variety of container structures
     *  different makes/models/firmware produce, which is why the pure-Java metadata-extractor
     *  fallback below has historically been unreliable for RAW orientation specifically (an
     *  earlier attempt at reading a Capture One-injected `<E K="Rotation" V="N"/>` field turned
     *  out not to be general CR3 metadata at all, just an artifact of files that happened to pass
     *  through Capture One — removed once ExifTool made that unnecessary). Falls back to the
     *  standard Exif Orientation tag only if ExifTool itself is unavailable (e.g. a local dev
     *  environment without it installed — see Dockerfile for where it's installed in prod). */
    private int resolveOrientation(byte[] rawBytes) {
        Integer exifToolOrientation = readOrientationViaExifTool(rawBytes);
        if (exifToolOrientation != null) return exifToolOrientation;
        return readExifOrientation(rawBytes);
    }

    /** Shells out to `exiftool -Orientation -n -s3 <file>`, which prints just the numeric Exif
     *  orientation (1-8) with no other output to parse. Returns null — not a thrown error — if
     *  the binary is missing, the process times out, or the file has no orientation tag, so
     *  {@link #resolveOrientation} can fall back instead of failing preview generation outright. */
    private Integer readOrientationViaExifTool(byte[] rawBytes) {
        java.io.File tempFile = null;
        Process process = null;
        try {
            tempFile = java.io.File.createTempFile("axion-orientation-", ".raw");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
                out.write(rawBytes);
            }

            process = new ProcessBuilder("exiftool", "-Orientation", "-n", "-s3", tempFile.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }

            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return null;
            }
            return output != null ? Integer.parseInt(output.trim()) : null;
        } catch (Exception e) {
            log.warn("ExifTool orientation lookup failed ({}), falling back to metadata-extractor", e.getMessage());
            return null;
        } finally {
            if (process != null) process.destroyForcibly();
            if (tempFile != null && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    /** EXIF Orientation tag (1-8, standard TIFF/Exif meaning), or 1 ("normal", no correction
     *  needed) if it's missing or unreadable. Scans every directory for the raw tag number
     *  (0x0112) rather than trusting it to live in {@code ExifIFD0Directory} specifically — the
     *  same reason the embedded-preview search above scans by tag number across every directory
     *  instead of one fixed type: CR3's ISO-BMFF-based container doesn't necessarily surface its
     *  Exif data the way metadata-extractor represents classic TIFF-structured RAW formats
     *  (CR2/NEF/ARW), so a directory-type-specific lookup can silently find nothing for it. */
    private int readExifOrientation(byte[] rawBytes) {
        try {
            com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(
                    new java.io.ByteArrayInputStream(rawBytes));
            for (com.drew.metadata.Directory dir : metadata.getDirectories()) {
                if (dir.containsTag(0x0112)) {
                    return dir.getInt(0x0112);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read EXIF orientation: {}", e.getMessage());
        }
        return 1;
    }

    /** Physically rotates/flips preview bytes to match the source file's EXIF Orientation tag
     *  (standard 1-8 values). No-ops (returns the original bytes) for orientation 1 or if
     *  anything about the correction fails — showing a sideways preview beats showing none. */
    private byte[] applyExifOrientation(byte[] jpegBytes, int orientation) {
        if (orientation <= 1) return jpegBytes;
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpegBytes));
            if (image == null) return jpegBytes;

            int width = image.getWidth();
            int height = image.getHeight();
            boolean swapsDimensions = orientation >= 5;
            int destWidth = swapsDimensions ? height : width;
            int destHeight = swapsDimensions ? width : height;

            java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
            switch (orientation) {
                case 2 -> { t.scale(-1.0, 1.0); t.translate(-width, 0); }
                case 3 -> { t.translate(width, height); t.rotate(Math.PI); }
                case 4 -> { t.scale(1.0, -1.0); t.translate(0, -height); }
                case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0); }
                case 6 -> { t.translate(height, 0); t.rotate(Math.PI / 2); }
                case 7 -> { t.scale(-1.0, 1.0); t.translate(-height, 0); t.translate(0, width); t.rotate(3 * Math.PI / 2); }
                case 8 -> { t.translate(0, width); t.rotate(3 * Math.PI / 2); }
                default -> { return jpegBytes; }
            }

            // Explicitly RGB (no alpha) rather than op.createCompatibleDestImage(image, null) —
            // AffineTransformOp can pick an ARGB destination for a rotation even though nothing
            // about a 90/180/270° turn of a fully-opaque JPEG source actually needs transparency.
            // JPEG has no writer for an alpha-carrying image; ImageIO.write() doesn't throw when
            // it can't find one, it just returns false and leaves the output stream empty — which
            // is exactly how this was silently producing a "successful" but 0-byte preview file
            // (uploaded fine, previewUrl set on the row, nothing actually renderable at that URL).
            java.awt.image.BufferedImage destination =
                    new java.awt.image.BufferedImage(destWidth, destHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.image.AffineTransformOp op =
                    new java.awt.image.AffineTransformOp(t, java.awt.image.AffineTransformOp.TYPE_BILINEAR);
            op.filter(image, destination);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            boolean wrote = javax.imageio.ImageIO.write(destination, "jpg", out);
            if (!wrote || out.size() == 0) {
                log.warn("No JPEG writer produced output for rotated preview (orientation {}), using unrotated bytes", orientation);
                return jpegBytes;
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("EXIF orientation correction failed, using unrotated preview: {}", e.getMessage());
            return jpegBytes;
        }
    }

    /** Uploads a JPEG preview to GCS, also setting width/height from its own dimensions, and
     *  returns its public URL. Filename includes a timestamp, not just the upload's id — see the
     *  identical comment in generateAndUploadPreview for why: a stable per-id URL meant re-saves
     *  of the same VE row silently kept serving the first-ever cached preview forever. */
    private String uploadPreviewJpeg(byte[] jpegBytes, ImageUpload imageUpload) {
        try {
            java.awt.image.BufferedImage previewImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpegBytes));
            if (previewImg != null) {
                imageUpload.setWidth(previewImg.getWidth());
                imageUpload.setHeight(previewImg.getHeight());
            }
        } catch (Exception ignore) {}

        String previewFileName = "previews/" + imageUpload.getId() + "_" + System.currentTimeMillis() + "_preview.jpg";
        uploadToGcs(previewFileName, jpegBytes, "image/jpeg");
        return "https://storage.googleapis.com/" + bucketName + "/" + previewFileName;
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
            // GCS object names (and so these public URLs) can contain raw spaces from the
            // original filename (e.g. "IMG_7249 2.HEIC") — URI.create() rejects those outright,
            // so percent-encode the one character we know appears in practice rather than
            // failing every download for such a file.
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(imageUrl.replace(" ", "%20")))
                    .timeout(java.time.Duration.ofSeconds(60))
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
                u.getApprovalStatus(),
                u.isEstablished()
        );
    }

    /**
     * Returns all versions of a file (v1 first), identified by any version's upload ID.
     */
    public List<ImageUploadDto> getAllVersions(Long uploadId) {
        return resolveVersionChain(uploadId).stream().map(this::toDto).collect(Collectors.toList());
    }

    /** Every version row in the same chain as {@code uploadId} (v1 first), regardless of which
     *  version's id is passed in. Empty if the upload doesn't exist. */
    private List<ImageUpload> resolveVersionChain(Long uploadId) {
        ImageUpload upload = imageUploadRepository.findById(uploadId).orElse(null);
        if (upload == null) return List.of();

        Long rootId = upload.getOriginalUploadId() != null ? upload.getOriginalUploadId() : uploadId;
        ImageUpload v1 = imageUploadRepository.findById(rootId).orElse(null);
        if (v1 == null) return List.of();

        List<ImageUpload> versions = new ArrayList<>();
        versions.add(v1);
        versions.addAll(imageUploadRepository.findByOriginalUploadIdOrderByVersionNumberAsc(rootId));
        return versions;
    }

    /** Marks {@code uploadId} as the established (VE) version — the current source-of-truth,
     *  original-format working file to rework from — clearing the flag from whichever other
     *  version in the same chain held it before. Called whenever an editor saves through the
     *  desktop app's edit-and-resync flow (see BatchService#uploadSingleImageSync);
     *  deliberately not tied to approval, since the approved/"latest" version could be a
     *  flattened export uploaded through a different path. */
    @Transactional
    public void markEstablished(Long uploadId) {
        for (ImageUpload version : resolveVersionChain(uploadId)) {
            boolean shouldBeEstablished = version.getId().equals(uploadId);
            if (version.isEstablished() != shouldBeEstablished) {
                version.setEstablished(shouldBeEstablished);
                imageUploadRepository.save(version);
            }
        }
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
        long origId = latest.getOriginalUploadId() != null ? latest.getOriginalUploadId() : latest.getId();

        // VE (established) is a perpetual draft slot, not a step in the v1/v2/v3... sequence —
        // a fresh finalized upload lands right after the highest *finalized* version, regardless
        // of whatever internal version number VE's own row happens to hold (it was assigned once
        // when VE was created and is never shown — the frontend always displays an established
        // row as "VE" — so it must never determine the next finalized number).
        long nextVer = existing.stream()
                .filter(u -> !u.isEstablished())
                .mapToLong(u -> u.getVersionNumber() != null ? u.getVersionNumber() : 1)
                .max().orElse(0) + 1L;

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
