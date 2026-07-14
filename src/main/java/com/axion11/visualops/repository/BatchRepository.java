package com.axion11.visualops.repository;

import com.axion11.visualops.models.Batch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {
    List<Batch> findByProjectId(Long projectId);

    /** Stable ordering for list views — plain findAll() has no guaranteed row order. */
    List<Batch> findAllByOrderByIdAsc();

    /** Same as above but eagerly fetches the (lazy) project association to avoid N+1 per batch. */
    @Query("SELECT b FROM Batch b LEFT JOIN FETCH b.project ORDER BY b.id ASC")
    List<Batch> findAllWithProjectOrderByIdAsc();

    List<Batch> findByProjectIdAndParentBatchIsNull(Long projectId);

    List<Batch> findByParentBatchId(Long parentBatchId);

    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.totalImages = b.totalImages + :count, b.uploadStatus = :status WHERE b.id = :batchId")
    void addTotalImagesAndSetStatus(@Param("batchId") Long batchId, @Param("count") int count, @Param("status") String status);

    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.uploadedImages = b.uploadedImages + 1 WHERE b.id = :batchId")
    void incrementUploadedImages(@Param("batchId") Long batchId);

    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.uploadStatus = :status, b.status = :activeStatus WHERE b.id = :batchId")
    void updateStatuses(@Param("batchId") Long batchId, @Param("status") String status, @Param("activeStatus") String activeStatus);

    /** Atomically mark batch COMPLETED only if all images have been uploaded. */
    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.uploadStatus = 'COMPLETED', b.status = 'ACTIVE' WHERE b.id = :batchId AND b.uploadedImages >= b.totalImages AND b.totalImages > 0")
    int completeIfAllUploaded(@Param("batchId") Long batchId);

    /** Reset upload counters for a new upload session. */
    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.totalImages = :total, b.uploadedImages = 0, b.uploadStatus = 'UPLOADING' WHERE b.id = :batchId")
    void resetUploadProgress(@Param("batchId") Long batchId, @Param("total") int total);

    /** Trash: soft-deleted batches (bypasses @SQLRestriction via native SQL). */
    @Query(value = "SELECT * FROM batches WHERE deleted_at IS NOT NULL AND parent_batch_id IS NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Batch> findAllInTrash();

    @Modifying
    @Transactional
    @Query(value = "UPDATE batches SET deleted_at = :now WHERE id = :id", nativeQuery = true)
    void softDeleteById(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(value = "UPDATE batches SET deleted_at = NULL WHERE id = :id", nativeQuery = true)
    void restoreById(@Param("id") Long id);

    @Query(value = "SELECT id FROM batches WHERE parent_batch_id = :parentId", nativeQuery = true)
    List<Long> findChildIdsByParentId(@Param("parentId") Long parentId);

    /** Name lookup that bypasses @SQLRestriction — needed because an upload's batch may itself be soft-deleted. */
    @Query(value = "SELECT name FROM batches WHERE id = :id", nativeQuery = true)
    Optional<String> findNameById(@Param("id") Long id);

    @Query(value = """
            SELECT b.id,
                   b.name,
                   b.deleted_at,
                   p.name            AS project_name,
                   b.assigned_to,
                   pb.name           AS parent_batch_name
            FROM batches b
            JOIN projects p ON p.id = b.project_id
            LEFT JOIN batches pb ON pb.id = b.parent_batch_id
            WHERE b.deleted_at IS NOT NULL
            ORDER BY b.deleted_at DESC
            """, nativeQuery = true)
    List<Object[]> findTrashSummary();

    /**
     * Returns IDs of only the root-level deleted batches (parent_batch_id IS NULL, or parent
     * itself is NOT deleted). Used by clearAll() to avoid double-processing children that
     * permanentDeleteBatchRecursive() already deletes when it processes the parent.
     */
    @Query(value = """
            SELECT b.id FROM batches b
            WHERE b.deleted_at IS NOT NULL
              AND (b.parent_batch_id IS NULL
                   OR NOT EXISTS (
                       SELECT 1 FROM batches pb
                       WHERE pb.id = b.parent_batch_id AND pb.deleted_at IS NOT NULL
                   ))
            """, nativeQuery = true)
    List<Long> findRootTrashBatchIds();

    /** Same as findRootTrashBatchIds but only batches deleted before :cutoff. */
    @Query(value = """
            SELECT b.id FROM batches b
            WHERE b.deleted_at IS NOT NULL
              AND b.deleted_at < :cutoff
              AND (b.parent_batch_id IS NULL
                   OR NOT EXISTS (
                       SELECT 1 FROM batches pb
                       WHERE pb.id = b.parent_batch_id AND pb.deleted_at IS NOT NULL
                   ))
            """, nativeQuery = true)
    List<Long> findRootTrashBatchIdsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Returns upload IDs that are individually soft-deleted (deleted_at IS NOT NULL) but whose
     * batch is NOT itself soft-deleted. Used by clearAll() to avoid re-processing uploads that
     * are covered by a trashed batch's recursive delete.
     */
    @Query(value = """
            SELECT u.id FROM image_uploads u
            LEFT JOIN batches b ON b.id = u.batch_id
            WHERE u.deleted_at IS NOT NULL
              AND (u.batch_id IS NULL OR b.deleted_at IS NULL)
            """, nativeQuery = true)
    List<Long> findOrphanTrashUploadIds();
}
