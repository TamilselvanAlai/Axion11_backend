package com.axion11.visualops.service;

import com.axion11.visualops.controller.dto.BatchDto;
import com.axion11.visualops.controller.dto.BatchRequest;
import com.axion11.visualops.controller.dto.ImageTagDto;
import com.axion11.visualops.controller.dto.ImageUploadDto;
import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchService {

    /** Pre-read file data that survives after the HTTP request completes. */
    public record FileData(String originalFilename, String contentType, long size, byte[] bytes, java.io.File tempFile) {
        /** Constructor for small files kept in memory. */
        public FileData(String originalFilename, String contentType, long size, byte[] bytes) {
            this(originalFilename, contentType, size, bytes, null);
        }
    }

    /** Threshold above which files are saved to temp disk instead of byte arrays. */
    private static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024; // 50 MB


    private final BatchRepository batchRepository;
    private final ProjectRepository projectRepository;
    private final TeamRepository teamRepository;
    private final ImageUploadService imageUploadService;
    private final ImageUploadRepository imageUploadRepository;
    private final ImageQcResultRepository imageQcResultRepository;
    private final ExecutorService imageUploadExecutor;
    private final ExecutorService aiAnalysisExecutor;
    private final ProjectAccessService projectAccessService;

    /**
     * Creates a batch record and returns it immediately.
     * Image uploads are processed asynchronously via {@link #uploadImagesAsync}.
     */
    public BatchDto createBatch(BatchRequest request, String uploaderEmail) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new NoSuchElementException("Project not found: " + request.projectId()));

        Batch batch = Batch.builder()
                .name(request.name())
                .project(project)
                .notes(request.notes())
                .assignedTo(request.assignedTo())
                .dueDate(request.eta())
                .status("PENDING")
                .uploadStatus("PENDING")
                .totalImages(0)
                .uploadedImages(0)
                .build();

        if (request.parentBatchId() != null) {
            Batch parent = batchRepository.findById(request.parentBatchId())
                    .orElseThrow(() -> new NoSuchElementException("Parent batch not found: " + request.parentBatchId()));
            batch.setParentBatch(parent);
        }

        batch = batchRepository.save(batch);
        return toDto(batch);
    }

    /**
     * Asynchronously uploads images to GCS and links them to the batch.
     * Phase 1 (fast): GCS upload + metadata — completes quickly, marks batch done.
     * Phase 2 (deferred): Vision API + Gemini AI tagging — runs in background.
     */
    @Async
    public void uploadImagesAsync(Long batchId, List<FileData> files, String uploaderEmail) {
        final Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + batchId));

        // totalImages and uploadStatus are set by the start-upload endpoint before chunks arrive.

        AtomicInteger uploaded = new AtomicInteger(0);
        AtomicBoolean hasFailure = new AtomicBoolean(false);
        Long projectId = batch.getProject().getId();

        // Phase 1: Fast GCS upload + metadata (no AI calls)
        List<Future<?>> futures = new ArrayList<>();
        List<long[]> deferredAnalysis = java.util.Collections.synchronizedList(new ArrayList<>());
        List<byte[]> deferredBytes = java.util.Collections.synchronizedList(new ArrayList<>());

        for (FileData fileData : files) {
            futures.add(imageUploadExecutor.submit(() -> {
                try {
                    ImageUploadDto result;
                    boolean isLargeFile = fileData.tempFile() != null;

                    if (isLargeFile) {
                        // Large file: stream from temp file to GCS
                        result = imageUploadService.uploadImageFastFromFile(
                                fileData.originalFilename(),
                                fileData.contentType(),
                                fileData.size(),
                                fileData.tempFile(),
                                projectId,
                                uploaderEmail,
                                batchId
                        );
                    } else {
                        // Small file: upload from byte array
                        result = imageUploadService.uploadImageFast(
                                fileData.originalFilename(),
                                fileData.contentType(),
                                fileData.size(),
                                fileData.bytes(),
                                projectId,
                                uploaderEmail,
                                batchId
                        );
                    }

                    // Link the uploaded image to this batch
                    ImageUpload imageUpload = imageUploadRepository.findById(result.id()).orElse(null);
                    if (imageUpload != null) {
                        imageUpload.setBatch(batch);
                        imageUpload.setUploadStatus("COMPLETED");
                        imageUploadRepository.save(imageUpload);
                    }
                    uploaded.incrementAndGet();

                    // Atomically increment in DB to avoid race conditions across concurrent chunks
                    batchRepository.incrementUploadedImages(batchId);

                    // Queue for deferred AI analysis (only for small/image files)
                    if (!isLargeFile && fileData.bytes() != null) {
                        deferredAnalysis.add(new long[]{result.id()});
                        deferredBytes.add(fileData.bytes());
                    }
                } catch (Exception e) {
                    log.error("Failed to upload file {} for batch {}: {}", fileData.originalFilename(), batchId, e.getMessage(), e);
                    hasFailure.set(true);
                } finally {
                    // Clean up temp file
                    if (fileData.tempFile() != null) {
                        fileData.tempFile().delete();
                    }
                }
            }));
        }

        // Wait for GCS uploads to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Error waiting for image upload in batch {}: {}", batchId, e.getMessage());
                hasFailure.set(true);
            }
        }

        // Atomically mark COMPLETED only if all images across all chunks are done.
        // Uses a DB-level WHERE check to avoid Hibernate L1 cache staleness.
        int finalUploaded = uploaded.get();
        if (hasFailure.get() && finalUploaded == 0) {
            batchRepository.updateStatuses(batchId, "FAILED", "FAILED");
        } else {
            batchRepository.completeIfAllUploaded(batchId);
        }

        log.info("Batch {} chunk complete: {}/{} images. Starting deferred AI analysis...",
                batchId, finalUploaded, files.size());

        // Phase 2: Deferred AI analysis — run with bounded concurrency on aiAnalysisExecutor (3 threads).
        // Face-group writes inside ImageUploadService are guarded by a per-project lock to prevent
        // races when two threads detect the same new person simultaneously.
        List<Future<?>> aiFutures = new ArrayList<>();
        for (int i = 0; i < deferredAnalysis.size(); i++) {
            final long imageId = deferredAnalysis.get(i)[0];
            final byte[] imageBytes = deferredBytes.get(i);
            aiFutures.add(aiAnalysisExecutor.submit(() -> {
                try {
                    imageUploadService.analyzeImageDeferred(imageId, imageBytes);
                } catch (Exception e) {
                    log.error("Deferred AI analysis failed for image {} in batch {}: {}", imageId, batchId, e.getMessage());
                }
            }));
        }
        for (Future<?> f : aiFutures) {
            try { f.get(); } catch (Exception e) {
                log.error("Phase 2 task wait failed for batch {}: {}", batchId, e.getMessage());
            }
        }
    }

    @Transactional
    public BatchDto assignBatch(Long id, Long teamId) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + id));
        if (teamId != null) {
            Team team = teamRepository.findById(teamId)
                    .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));
            batch.setTeam(team);
            batch.setAssignedTo(team.getTeamName());
        } else {
            batch.setTeam(null);
            batch.setAssignedTo(null);
        }
        batch = batchRepository.save(batch);
        return toDto(batch);
    }

    /**
     * Moves a batch under a new parent batch (sub-batch nesting) or to the top level of a
     * different project. Exactly one of parentBatchId / projectId should be provided — moving
     * under a parent batch always inherits that parent's project, so the batch and every upload
     * nested anywhere beneath it gets re-pointed to keep project membership consistent.
     */
    @Transactional
    public BatchDto moveBatch(Long id, Long parentBatchId, Long projectId) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + id));

        Batch newParent = null;
        Project targetProject;
        if (parentBatchId != null) {
            newParent = batchRepository.findById(parentBatchId)
                    .orElseThrow(() -> new NoSuchElementException("Target batch not found: " + parentBatchId));
            if (parentBatchId.equals(id) || isDescendantOf(newParent, id)) {
                throw new IllegalArgumentException("Cannot move a batch into itself or one of its own sub-batches");
            }
            targetProject = newParent.getProject();
        } else if (projectId != null) {
            targetProject = projectRepository.findById(projectId)
                    .orElseThrow(() -> new NoSuchElementException("Project not found: " + projectId));
        } else {
            throw new IllegalArgumentException("Either parentBatchId or projectId is required");
        }

        batch.setParentBatch(newParent);
        batch.setProject(targetProject);
        batch = batchRepository.save(batch);

        if (targetProject != null) {
            reassignProjectRecursively(batch, targetProject);
        }

        return toDto(batch);
    }

    /** True if {@code candidateAncestorId} appears anywhere in node's ancestor chain (including itself). */
    private boolean isDescendantOf(Batch node, Long candidateAncestorId) {
        Batch current = node;
        while (current != null) {
            if (current.getId().equals(candidateAncestorId)) return true;
            current = current.getParentBatch();
        }
        return false;
    }

    /** Keeps every nested sub-batch and upload's project field in sync after a cross-project move. */
    private void reassignProjectRecursively(Batch batch, Project targetProject) {
        List<ImageUpload> uploads = imageUploadRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId());
        for (ImageUpload upload : uploads) {
            upload.setProject(targetProject);
        }
        imageUploadRepository.saveAll(uploads);
        for (Batch child : batchRepository.findByParentBatchId(batch.getId())) {
            child.setProject(targetProject);
            batchRepository.save(child);
            reassignProjectRecursively(child, targetProject);
        }
    }

    @Transactional
    public BatchDto renameBatch(Long id, String newName) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + id));
        batch.setName(newName);
        batch = batchRepository.save(batch);
        return toDto(batch);
    }

    @Transactional
    public void deleteBatch(Long id) {
        // Soft-delete: mark this batch and all child batches/uploads as deleted
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        softDeleteBatchRecursive(id, now);
        log.info("Soft-deleted batch {}", id);
    }

    private void softDeleteBatchRecursive(Long id, java.time.LocalDateTime now) {
        // Soft-delete all child batches first
        List<Long> childIds = batchRepository.findChildIdsByParentId(id);
        for (Long childId : childIds) {
            softDeleteBatchRecursive(childId, now);
        }
        // Soft-delete all uploads in this batch
        List<ImageUpload> uploads = imageUploadRepository.findByBatchIdOrderByCreatedAtDesc(id);
        for (ImageUpload upload : uploads) {
            imageUploadRepository.softDeleteById(upload.getId(), now);
        }
        // Soft-delete the batch itself
        batchRepository.softDeleteById(id, now);
    }

    public BatchDto getBatch(Long id) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + id));
        if (batch.getProject() != null && !projectAccessService.canAccess(batch.getProject())) {
            // Hide cross-team batches behind a 404 so we don't leak existence.
            throw new NoSuchElementException("Batch not found: " + id);
        }
        return toDto(batch);
    }

    public List<BatchDto> getBatchesByProject(Long projectId) {
        java.util.Set<Long> allowed = projectAccessService.allowedProjectIds();
        if (projectId != null) {
            if (allowed != null && !allowed.contains(projectId)) return List.of();
            return batchRepository.findByProjectId(projectId).stream()
                    .map(this::toDto).collect(Collectors.toList());
        }
        java.util.stream.Stream<Batch> stream = batchRepository.findAll().stream();
        if (allowed != null) {
            stream = stream.filter(b -> b.getProject() != null && allowed.contains(b.getProject().getId()));
        }
        return stream.map(this::toDto).collect(Collectors.toList());
    }

    public BatchDto toBatchDtoMinimal(Batch batch) {
        return new BatchDto(
                batch.getId(),
                batch.getName(),
                batch.getProject().getId(),
                batch.getProject().getName(),
                batch.getParentBatch() != null ? batch.getParentBatch().getId() : null,
                batch.getStatus(),
                batch.getUploadStatus(),
                batch.getTotalImages(),
                batch.getUploadedImages(),
                batch.getAssignedTo(),
                batch.getTeam() != null ? batch.getTeam().getId() : null,
                batch.getTeam() != null ? batch.getTeam().getTeamName() : null,
                batch.getDueDate(),
                batch.getPriority(),
                batch.getNotes(),
                batch.getCreatedAt(),
                List.of()
        );
    }

    public BatchDto updateBatchDetails(Long id, String dueDate, String priority) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + id));
        if (dueDate != null) batch.setDueDate(dueDate);
        if (priority != null) batch.setPriority(priority);
        return toDto(batchRepository.save(batch));
    }

    private BatchDto toDto(Batch batch) {
        List<ImageUpload> uploads = imageUploadRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId());
        List<ImageUploadDto> uploadDtos = uploads.stream().map(u -> {
            List<ImageTagDto> tagDtos = u.getTags().stream()
                    .map(t -> new ImageTagDto(t.getId(), t.getCategory(), t.getValue(), t.getConfidence()))
                    .collect(Collectors.toList());
            return new ImageUploadDto(
                    u.getId(),
                    u.getFileName(),
                    u.getPublicUrl(),
                    u.getContentType(),
                    u.getFileSize(),
                    u.getProject() != null ? u.getProject().getId() : null,
                    u.getBatch() != null ? u.getBatch().getId() : null,
                    u.getUploadedBy() != null ? u.getUploadedBy().getEmail() : null,
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
        }).collect(Collectors.toList());

        return new BatchDto(
                batch.getId(),
                batch.getName(),
                batch.getProject().getId(),
                batch.getProject().getName(),
                batch.getParentBatch() != null ? batch.getParentBatch().getId() : null,
                batch.getStatus(),
                batch.getUploadStatus(),
                batch.getTotalImages(),
                batch.getUploadedImages(),
                batch.getAssignedTo(),
                batch.getTeam() != null ? batch.getTeam().getId() : null,
                batch.getTeam() != null ? batch.getTeam().getTeamName() : null,
                batch.getDueDate(),
                batch.getPriority(),
                batch.getNotes(),
                batch.getCreatedAt(),
                uploadDtos
        );
    }
}
