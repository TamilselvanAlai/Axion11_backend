package com.axion11.visualops.service;

import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.repository.BatchRepository;
import com.axion11.visualops.repository.CommentRepository;
import com.axion11.visualops.repository.ImageQcResultRepository;
import com.axion11.visualops.repository.ImageTagRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.SyncedFileRepository;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrashService {

    private final ImageUploadRepository imageUploadRepository;
    private final BatchRepository batchRepository;
    private final ImageQcResultRepository imageQcResultRepository;
    private final ImageTagRepository imageTagRepository;
    private final CommentRepository commentRepository;
    private final SyncedFileRepository syncedFileRepository;
    private final EntityManager entityManager;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    public List<Map<String, Object>> getTrashItems() {
        List<Map<String, Object>> items = new ArrayList<>();

        // Soft-deleted batches (all levels, with project + parent info)
        List<Object[]> trashBatches = batchRepository.findTrashSummary();
        for (Object[] row : trashBatches) {
            Long id             = ((Number) row[0]).longValue();
            String name         = (String) row[1];
            Object deletedAt    = row[2];
            String projectName  = (String) row[3];
            String assignedTo   = (String) row[4];   // owner / assigned_to
            String parentName   = (String) row[5];   // null for top-level batches

            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("id",          id);
            item.put("name",        name);
            item.put("type",        "batch");
            item.put("deletedAt",   deletedAt != null ? deletedAt.toString() : "");
            item.put("projectName", projectName != null ? projectName : "");
            item.put("batchPath",   parentName != null ? parentName + " / " + name : name);
            item.put("owner",       assignedTo != null ? assignedTo : "");
            item.put("deletedBy",   assignedTo != null ? assignedTo : "");
            items.add(item);
        }

        // Soft-deleted uploads (individually deleted, not batch-deleted)
        List<ImageUpload> trashUploads = imageUploadRepository.findAllInTrash();
        for (ImageUpload upload : trashUploads) {
            String uploaderName = upload.getUploadedBy() != null
                    ? (upload.getUploadedBy().getName() != null ? upload.getUploadedBy().getName() : upload.getUploadedBy().getEmail())
                    : "";
            String projectName = upload.getProject() != null ? upload.getProject().getName() : "";
            // Don't call upload.getBatch().getName() directly: Batch carries @SQLRestriction("deleted_at IS NULL"),
            // which Hibernate also enforces when initializing this lazy proxy by id — so if the upload's batch is
            // itself soft-deleted, the proxy load finds no matching row and throws ObjectNotFoundException instead
            // of returning null. Getting the id off the proxy doesn't trigger that load; look the name up separately.
            String batchName = upload.getBatch() != null
                    ? batchRepository.findNameById(upload.getBatch().getId()).orElse("")
                    : "";
            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("id",          upload.getId());
            item.put("name",        upload.getFileName());
            item.put("type",        "upload");
            item.put("deletedAt",   upload.getDeletedAt() != null ? upload.getDeletedAt().toString() : "");
            item.put("publicUrl",   upload.getPublicUrl() != null ? upload.getPublicUrl() : "");
            item.put("projectName", projectName);
            item.put("batchPath",   batchName);
            item.put("owner",       uploaderName);
            item.put("deletedBy",   uploaderName);
            items.add(item);
        }

        return items;
    }

    @Transactional
    public void restoreUpload(Long id) {
        imageUploadRepository.restoreById(id);
    }

    @Transactional
    public void restoreBatch(Long id) {
        restoreBatchRecursive(id);
    }

    private void restoreBatchRecursive(Long id) {
        batchRepository.restoreById(id);
        imageUploadRepository.restoreUploadsByBatch(id);
        List<Long> childIds = batchRepository.findChildIdsByParentId(id);
        for (Long childId : childIds) {
            restoreBatchRecursive(childId);
        }
    }

    @Transactional
    public void permanentDeleteUpload(Long id) {
        // Fetch GCS path before deleting DB record
        imageUploadRepository.findById(id).ifPresent(upload -> {
            deleteGcsFile(upload.getGcsPath());
            if (upload.getPreviewUrl() != null) {
                String previewPath = upload.getPreviewUrl()
                        .replace("https://storage.googleapis.com/" + bucketName + "/", "");
                deleteGcsFile(previewPath);
            }
        });
        // hardDeleteById is a native DELETE, which bypasses JPA's @OneToMany cascades — so rows
        // in any table with a FK to this upload must be cleared first, or MySQL rejects the
        // delete with a FK constraint violation (this is what was 500ing every permanent delete).
        imageQcResultRepository.deleteByImageUploadId(id);
        imageTagRepository.deleteByImageUploadId(id);
        commentRepository.deleteByImageUploadId(id);
        syncedFileRepository.orphanByImageUploadIds(List.of(id));
        imageUploadRepository.hardDeleteById(id);
    }

    @Transactional
    public void permanentDeleteBatch(Long id) {
        permanentDeleteBatchRecursive(id);
    }

    private void permanentDeleteBatchRecursive(Long id) {
        List<Long> childIds = batchRepository.findChildIdsByParentId(id);
        for (Long childId : childIds) {
            permanentDeleteBatchRecursive(childId);
        }
        // Delete all uploads (including soft-deleted) in this batch from GCS + DB
        List<ImageUpload> uploads = imageUploadRepository.findAllByBatchIdIncludingDeleted(id);
        for (ImageUpload upload : uploads) {
            deleteGcsFile(upload.getGcsPath());
            if (upload.getPreviewUrl() != null) {
                String previewPath = upload.getPreviewUrl()
                        .replace("https://storage.googleapis.com/" + bucketName + "/", "");
                deleteGcsFile(previewPath);
            }
        }
        // deleteAllByBatchId is a native DELETE, which bypasses JPA's @OneToMany cascades — so
        // rows in any table with a FK to these uploads must be cleared first (same reasoning as
        // permanentDeleteUpload above).
        if (!uploads.isEmpty()) {
            List<Long> uploadIds = uploads.stream().map(ImageUpload::getId).toList();
            imageQcResultRepository.deleteByImageUploadIdIn(uploadIds);
            imageTagRepository.deleteByImageUploadIdIn(uploadIds);
            commentRepository.deleteByImageUploadIdIn(uploadIds);
            syncedFileRepository.orphanByImageUploadIds(uploadIds);
        }
        // Delete uploads then batch
        imageUploadRepository.deleteAllByBatchId(id);
        batchRepository.deleteById(id);
    }

    @Transactional
    public int clearOldItems() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);

        // Only delete uploads older than cutoff whose batch is NOT itself in trash
        // (batch deletes are handled by the batch path below to avoid double-processing).
        List<Long> oldUploadIds = imageUploadRepository.findIdsOlderThan(cutoff);
        // Filter: only those whose batch is not itself deleted
        List<Long> orphanUploadIds = batchRepository.findOrphanTrashUploadIds();
        List<Long> safeUploadIds = oldUploadIds.stream()
                .filter(orphanUploadIds::contains)
                .toList();
        for (Long uploadId : safeUploadIds) {
            permanentDeleteUpload(uploadId);
        }

        // Delete root-level trashed batches older than cutoff (recursively handles children)
        List<Long> rootBatchIds = batchRepository.findRootTrashBatchIdsOlderThan(cutoff);
        int batchesCleared = 0;
        for (Long batchId : rootBatchIds) {
            // Check if the batch itself is older than cutoff
            // (findRootTrashBatchIds doesn't filter by age, so we check via the upload cutoff)
            permanentDeleteBatch(batchId);
            batchesCleared++;
        }

        int total = safeUploadIds.size() + batchesCleared;
        log.info("Auto-cleared {} uploads + {} root batches older than 30 days from trash", safeUploadIds.size(), batchesCleared);
        return total;
    }

    @Transactional
    public int clearAll() {
        // Step 1: Permanently delete all root-level trashed batches (children are deleted
        // recursively inside permanentDeleteBatchRecursive, so we must NOT iterate children
        // separately — doing so caused the 500 by trying to delete already-deleted rows).
        List<Long> rootBatchIds = batchRepository.findRootTrashBatchIds();
        int batchCount = 0;
        for (Long batchId : rootBatchIds) {
            permanentDeleteBatch(batchId);
            batchCount++;
        }

        // Step 2: Permanently delete uploads that are individually trashed but whose batch
        // was NOT itself in the trash (those inside deleted batches were already cleaned above).
        List<Long> orphanUploadIds = batchRepository.findOrphanTrashUploadIds();
        int uploadCount = 0;
        for (Long uploadId : orphanUploadIds) {
            permanentDeleteUpload(uploadId);
            uploadCount++;
        }

        log.info("clearAll: deleted {} root batches, {} orphan uploads", batchCount, uploadCount);
        return batchCount + uploadCount;
    }

    /** Runs daily at 2:00 AM to auto-purge trash items older than 30 days. */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledCleanup() {
        try {
            int cleared = clearOldItems();
            log.info("Scheduled trash cleanup: removed {} items", cleared);
        } catch (Exception e) {
            log.error("Scheduled trash cleanup failed", e);
        }
    }

    private void deleteGcsFile(String gcsPath) {
        if (gcsPath == null || gcsPath.isBlank()) return;
        try {
            // Strip gs://bucket/ prefix if present to get the plain object name
            String objectName = gcsPath.startsWith("gs://")
                    ? gcsPath.replaceFirst("^gs://[^/]+/", "")
                    : gcsPath;
            Storage storage = StorageOptions.getDefaultInstance().getService();
            storage.delete(BlobId.of(bucketName, objectName));
        } catch (Exception e) {
            log.warn("Failed to delete GCS file {}: {}", gcsPath, e.getMessage());
        }
    }
}
