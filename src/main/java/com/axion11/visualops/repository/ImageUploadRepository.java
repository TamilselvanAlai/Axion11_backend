package com.axion11.visualops.repository;

import com.axion11.visualops.models.ImageUpload;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImageUploadRepository extends JpaRepository<ImageUpload, Long> {
    // These power the main asset list views (Assets grid/table) — @EntityGraph fetches the
    // lazy project/batch/uploadedBy associations (and tags) in the same query instead of one
    // extra query per association per row, which otherwise scales with list size.
    @EntityGraph(attributePaths = {"tags", "project", "batch", "uploadedBy"})
    List<ImageUpload> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    @EntityGraph(attributePaths = {"tags", "project", "batch", "uploadedBy"})
    List<ImageUpload> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"tags", "project", "batch", "uploadedBy"})
    List<ImageUpload> findByBatchIdOrderByCreatedAtDesc(Long batchId);

    List<ImageUpload> findByImageQualityQcCheckIsNullOrImageQualityQcCheckNot(String status);

    Optional<ImageUpload> findByExternalId(String externalId);

    /** All versions of a file with the same name in the same batch, newest first. */
    List<ImageUpload> findByFileNameAndBatchIdOrderByVersionNumberDesc(String fileName, Long batchId);

    /** All versions that share the same original upload (v2, v3, …), oldest first. */
    List<ImageUpload> findByOriginalUploadIdOrderByVersionNumberAsc(Long originalUploadId);

    /** Locks a chain's root row (v1) for the rest of the current transaction — used by the
     *  edit-and-resync flow (see ImageUploadService#syncEditedVersion) so two concurrent saves
     *  for the same asset serialize through the database. A plain in-process `synchronized`
     *  block only protects against races within a single JVM; Cloud Run can run multiple
     *  container instances, so two near-simultaneous saves landing on different instances would
     *  both see "no established version yet" and each create their own. A DB-level lock blocks
     *  the second transaction until the first commits, regardless of which instance either runs on. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM ImageUpload u WHERE u.id = :id")
    Optional<ImageUpload> findByIdForUpdate(@Param("id") Long id);

    /** Uploads with no generated preview yet — candidates for the PSD/RAW/video preview backfill. */
    List<ImageUpload> findByPreviewUrlIsNull();

    /** Case-insensitive file-name substring search, scoped to one project — powers the DAM search bar. */
    @EntityGraph(attributePaths = {"tags", "project", "batch", "uploadedBy"})
    List<ImageUpload> findByProjectIdAndFileNameContainingIgnoreCaseOrderByCreatedAtDesc(Long projectId, String fileName);

    /** Case-insensitive file-name substring search across every project — DAM search bar with no project filter. */
    @EntityGraph(attributePaths = {"tags", "project", "batch", "uploadedBy"})
    List<ImageUpload> findByFileNameContainingIgnoreCaseOrderByCreatedAtDesc(String fileName);

    @Query("SELECT COUNT(u) FROM ImageUpload u WHERE LOWER(u.approvalStatus) = 'approved'")
    long countApproved();

    @Query("SELECT COUNT(u) FROM ImageUpload u WHERE LOWER(u.approvalStatus) = 'rejected'")
    long countRejected();

    @Query("SELECT COUNT(u) FROM ImageUpload u WHERE u.approvalStatus IS NULL OR LOWER(u.approvalStatus) = 'pending'")
    long countPending();

    /** One grouped query for all batches' upload counts, instead of querying per batch (N+1). */
    @Query("SELECT u.batch.id, COUNT(u) FROM ImageUpload u WHERE u.batch IS NOT NULL GROUP BY u.batch.id")
    List<Object[]> countGroupedByBatchId();

    @Query("SELECT COUNT(u) FROM ImageUpload u WHERE u.batch.id = :batchId AND LOWER(u.approvalStatus) = 'approved'")
    int countApprovedByBatchId(@Param("batchId") Long batchId);

    @Query("SELECT COUNT(u) FROM ImageUpload u WHERE u.batch.id = :batchId AND LOWER(u.approvalStatus) = 'rejected'")
    int countRejectedByBatchId(@Param("batchId") Long batchId);

    @Query("SELECT COUNT(u) FROM ImageUpload u WHERE u.batch.id = :batchId AND (u.approvalStatus IS NULL OR LOWER(u.approvalStatus) = 'pending')")
    int countPendingByBatchId(@Param("batchId") Long batchId);

    /** Trash: all soft-deleted uploads ordered by deletion date (bypasses @SQLRestriction via native SQL). */
    @Query(value = "SELECT * FROM image_uploads WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<ImageUpload> findAllInTrash();

    @Modifying
    @Transactional
    @Query(value = "UPDATE image_uploads SET deleted_at = :now WHERE id = :id", nativeQuery = true)
    void softDeleteById(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(value = "UPDATE image_uploads SET deleted_at = NULL WHERE id = :id", nativeQuery = true)
    void restoreById(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM image_uploads WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    void hardDeleteById(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM image_uploads WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    void hardDeleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** Hard-deletes uploads by id regardless of deleted_at state — used when permanently
     *  deleting a whole project, where both active and already-trashed standalone uploads need
     *  to go. Bypasses @SQLRestriction (native SQL) so it can't silently no-op on trashed rows
     *  the way a plain deleteAllById() would. Callers must have already cleared any FK-referencing
     *  child rows (tags/comments/QC results/edit sessions) for these ids first. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM image_uploads WHERE id IN :ids", nativeQuery = true)
    void hardDeleteAllByIdIn(@Param("ids") Collection<Long> ids);

    @Query(value = "SELECT id, gcs_path, public_url FROM image_uploads WHERE deleted_at IS NOT NULL", nativeQuery = true)
    List<Object[]> findTrashGcsPaths();

    @Query(value = "SELECT id FROM image_uploads WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    List<Long> findIdsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query(value = "UPDATE image_uploads SET deleted_at = NULL WHERE batch_id = :batchId", nativeQuery = true)
    void restoreUploadsByBatch(@Param("batchId") Long batchId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM image_uploads WHERE batch_id = :batchId", nativeQuery = true)
    void deleteAllByBatchId(@Param("batchId") Long batchId);

    /** Find ALL uploads (including soft-deleted) for a batch, for use in trash permanent delete. */
    @Query(value = "SELECT * FROM image_uploads WHERE batch_id = :batchId", nativeQuery = true)
    List<ImageUpload> findAllByBatchIdIncludingDeleted(@Param("batchId") Long batchId);

    /** Find ALL uploads (including soft-deleted) attached directly to a project, for use when
     *  permanently deleting the whole project — a plain findByProjectId respects @SQLRestriction
     *  and silently misses any already-trashed upload, leaving its row behind to block the
     *  project's own delete with a dangling FK. */
    @Query(value = "SELECT * FROM image_uploads WHERE project_id = :projectId", nativeQuery = true)
    List<ImageUpload> findAllByProjectIdIncludingDeleted(@Param("projectId") Long projectId);
}
